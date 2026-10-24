package tv.own.owntv.features.sport

import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import tv.own.owntv.core.database.dao.ChannelDao
import tv.own.owntv.core.database.dao.EpgDao
import tv.own.owntv.core.database.entity.ChannelEntity
import tv.own.owntv.core.database.entity.EpgProgrammeEntity

/**
 * What the channel list of one fixture is filtered to (`docs/sport-roadmap.md` Punkt 4).
 *
 * @param languages the preferred languages (empty = all).
 * @param excludedLanguages languages dropped first — an exact per-event channel does NOT survive
 *   its language being excluded (excluding is an explicit "never show").
 * @param limit how many channels one fixture shows, clamped to [SportsMatcher.MinChannelLimit] ..
 *   [SportsMatcher.MaxChannelLimit].
 * @param minPreferred the minimum preferred-language hits for the list to count as covered: below
 *   it, legacy mode falls back to the full (post-exclude) list, while strict mode keeps the
 *   honestly shorter preferred list and never pads it with foreign feeds.
 * @param strict when true (default), no never-empty fallback — a short list stays short.
 */
data class SportMatchOptions(
    val languages: Set<SportLanguage> = emptySet(),
    val excludedLanguages: Set<SportLanguage> = emptySet(),
    val limit: Int = SportsMatcher.DefaultChannelLimit,
    val minPreferred: Int = SportsMatcher.MinPreferredCount,
    val strict: Boolean = true,
    /**
     * A country code ("UK", "US", "DE") whose channels get [SportsMatcher.PreferenceBoost] added
     * to their score before filtering, so they rank above equal- or better-scoring feeds from
     * elsewhere (Sky Sports above ESPN+ for the same fixture). Soft: other channels stay listed,
     * they just sort below. Null or blank = no preference, pure score ranking.
     */
    val preferCountry: String? = null,
    /**
     * Broadcaster names confirmed by an independent TV guide (Where's The Match / Live Football
     * On TV) for this fixture ("Sky Sports Premier League"). Probed against channel names and
     * scored [SportsMatcher.ConfirmedBroadcasterScore] — evidence the guide names the carrier,
     * which ESPN's US-centric broadcast field never would.
     */
    val confirmedBroadcasters: List<String> = emptyList(),
)

/**
 * Resolves an ESPN fixture to channels from the user's own playlists — Lume's EPG-based channel
 * matching (AGPL-3.0, https://github.com/bilipp/Lume), adapted to OwnTV's DAOs.
 *
 * ## Why this file looks the way it does
 *
 * The original matcher discovered candidates **only from channel names** (`channelDao.searchList`
 * → SQL `name LIKE '%token%'`); the EPG was then used merely to *score* a channel that the name
 * search had already found. That is why a channel such as `DE: SKY SPORT AUSTRIA 1 HD`, whose name
 * carries no team or broadcast word but whose guide says "Coventry City v Newcastle United", never
 * appeared — while Lume listed it. See `docs/sport-channel-matching.md` for the full analysis.
 *
 * The fix is a **second discovery source** (Lume's pass 2/3): probe the guide in the kickoff window
 * for each team token and pull in every channel those programmes point at, whether or not its own
 * name matched.
 *
 * ## Both teams are required (Lume-aligned, 2026-10-12)
 *
 * A candidate only qualifies on evidence that **both** teams are present — in the channel's own name,
 * or together in one EPG programme (title, or the description for a conference/foreign feed whose
 * generic title names nothing). A programme naming a single team is a news ticker, a soap set in the
 * club's city or a movie blurb, not the fixture: scoring it was what pulled `DE: SKY SPORT NEWS`,
 * `DE: SYFY`, `DE: DISNEY CHANNEL` into a football list. This mirrors Lume's
 * `ResolvedChannelSource` tiers (`epgTitleSubtitle` > `epgSingleField` > `epgDescription` >
 * `channelName`); Sportio keeps the weights additive instead of tier-typed.
 *
 * ## Duels and the tournament fallback (tennis/MMA, 2026-10-13)
 *
 * A duel (`VERSUS` with two entries and no home/away) runs the same two tiers with the two
 * athletes' names in place of the teams — plus two weaker, recall-oriented tiers below them:
 * a programme naming one athlete **together with tennis context** (the words tennis/ATP/WTA, a
 * tournament token or a sport category in the same programme) and a programme naming only the
 * tournament. A lone surname without any such context still scores nothing (`Paul` is a film
 * character far more often than Tommy Paul). The league's channel hint (tennis → "Tennis")
 * additionally resolves tennis-named channels (`US TENNIS PPV`, `SKY SPORT TENNIS`) by name, so
 * upcoming fixtures without any guide data still list their channels.
 *
 * A consequence to keep in mind: a feed that names only one team in its guide text (many German
 * broadcasters) is found only when a *nickname* supplies the other — hence the aliases in
 * `res/raw/sports_matching.json` (`Newcastle United → Magpies`).
 *
 * ## Region priority (US fixtures, 2026-10-14)
 *
 * A `US:`-tagged feed of a US-league game (college football, NFL, …) sorts above an equally
 * evidenced foreign feed (`CA:`, `AU:`, `UK:`) through a small score bonus in pass 2. Bonus-only,
 * never a filter: without a US feed the list keeps its foreign entries instead of going empty.
 *
 * ## Youth firewall (senior vs. youth, 2026-10-14)
 *
 * A senior fixture (e.g. `uefa.champions`) never scores a youth-marked programme or channel name
 * (`youth`, `jugend`, `u17–u23` incl. hyphenated `u-19`), and a youth fixture never scores a
 * non-youth one — symmetric, checked on normalized text via [SportNameMatch]. A generic channel
 * name without a marker still matches either side through its guide line; only the marker decides.
 *
 * ## Phases
 *
 *  - **Phase 1:** alias/exonym expansion + whole-word, accent-folded scoring + EPG discovery.
 *  - **Phase 2 (done):** description discovery (`EpgDao.searchGuide`), preferred-language filter.
 *  - **Phase 3 (planned):** remembered channel picks (Lume's `SportsChannelPicks`) and the
 *    tour/competition fallback for event sports (tennis, F1, golf) — see the doc.
 */
class SportsMatcher(
    private val channelDao: ChannelDao,
    private val epgDao: EpgDao,
    private val vocab: SportsVocab,
    /**
     * The profile's live playlists. Behind [LiveSourceIdsProvider] rather than the concrete
     * `SettingsRepository`/`SourceRepository` so the matcher is constructible without Android and can
     * be unit-tested with fake DAOs — see the interface's KDoc.
     */
    private val sources: LiveSourceIdsProvider,
) {

    /**
     * Channels that (probably) carry [fixture], strongest first, capped at [limit].
     *
     * [languages] is the viewer's preferred-language set (empty = all). The filter is applied
     * **before** the [limit] cut, and that order matters: a German feed such as
     * `DE: SKY SPORT PREMIER LEAGUE` names the clubs only in its EPG *description* and scores far
     * below the many English/Italian feeds that carry both teams in their titles, so a post-cut
     * filter would never see it. Filtering first lets the preferred languages win the fixed slots.
     *
     * Never throws for a malformed playlist or a cold EPG: every DAO call is `runCatching`-guarded
     * and an empty result simply means "nothing matched".
     */
    suspend fun match(
        fixture: Fixture,
        languages: Set<SportLanguage> = emptySet(),
        limit: Int = DefaultChannelLimit,
    ): List<ChannelMatch> =
        withContext(Dispatchers.IO) {
            // Legacy path: quota 1, so the full-list fallback triggers exactly when the filter
            // keeps nothing (the previous SportLanguages.filter behaviour).
            applyFilter(
                resolveSorted(fixture, emptyList()),
                SportMatchOptions(languages, limit = limit, minPreferred = 1, strict = false),
                demotePpv = fixture.isTeamSport(),
            )
        }

    /**
     * Channels that (probably) carry [fixture] under [options]: excluded languages dropped first,
     * then the preferred languages filtered **before** the [SportMatchOptions.limit] cut (a German
     * feed naming the clubs only in its EPG *description* scores far below the many
     * English/Italian title feeds, so a post-cut filter would never see it). In strict mode a
     * short preferred list stays short — no foreign feed is padded back in.
     *
     * Never throws for a malformed playlist or a cold EPG: every DAO call is `runCatching`-guarded
     * and an empty result simply means "nothing matched".
     */
    suspend fun match(fixture: Fixture, options: SportMatchOptions): List<ChannelMatch> =
        withContext(Dispatchers.IO) {
            applyFilter(resolveSorted(fixture, options.confirmedBroadcasters), options, demotePpv = fixture.isTeamSport())
        }

    /** Team sports have home/away sides; duels (tennis/MMA) and events (F1/golf) do not. */
    private fun Fixture.isTeamSport(): Boolean = home != null && away != null

    /**
     * Discovery (pass 1) + scoring (pass 2), strongest first, unfiltered. Pure IO; both [match]
     * overloads share it so only the final filter differs.
     */
    private suspend fun resolveSorted(fixture: Fixture, confirmedBroadcasters: List<String>): List<ChannelMatch> {
        val sourceIds = sources.liveSourceIds()
        if (sourceIds.isEmpty()) return emptyList()

        val home = fixture.home
        val away = fixture.away
        val teamSport = home != null && away != null
        // A duel is a VERSUS fixture with two entries and no home/away (tennis singles, MMA).
        // Doubles carry one "A / B" roster name per side; leaderboards and EVENTs never qualify.
        val duelSides = fixture.duelSides()
        val duelSport = !teamSport && duelSides != null

        val from = fixture.dateMs - EpgBeforeMs
        val to = fixture.dateMs + EpgAfterMs

        // Pass 1 — candidates. Channel names are matched in memory over one cached scan of the
        // profile's channels; the guide probes stay SQL (indexed window) and their rows double as
        // the EPG evidence for scoring, so pass 2 issues no queries at all.
        val epgEvidence = HashMap<String, MutableList<EpgProgrammeEntity>>()
        val candidates = discoverCandidates(fixture, teamSport, duelSport, sourceIds, from, to, epgEvidence, confirmedBroadcasters)
        if (candidates.isEmpty()) return emptyList()

        // Pass 2 — score each candidate against the fixture.
        val homeTokens = home?.scoringTokens().orEmpty()
        val awayTokens = away?.scoringTokens().orEmpty()
        val sideATokens = duelSides?.first?.sideTokens().orEmpty()
        val sideBTokens = duelSides?.second?.sideTokens().orEmpty()
        val tournamentTokens = fixture.tournamentTokens()

        return candidates.values.mapNotNull { channel ->
            scoreChannel(
                channel = channel,
                fixture = fixture,
                home = home,
                away = away,
                homeTokens = homeTokens,
                awayTokens = awayTokens,
                sideATokens = sideATokens,
                sideBTokens = sideBTokens,
                tournamentTokens = tournamentTokens,
                programmes = epgEvidence[channel.epgChannelId?.trim()?.lowercase(Locale.ROOT)].orEmpty(),
                confirmedBroadcasters = confirmedBroadcasters,
            )
        }.sortedByDescending { it.score }
    }

    /**
     * The final filter of [SportMatchOptions], always before the cap: exclude first (even exact
     * per-event channels), then the preferred languages — strict without fallback, legacy with the
     * full post-exclude list when fewer than [SportMatchOptions.minPreferred] survive.
     *
     * Ranking adjustments happen here, in one re-sort: a configured [SportMatchOptions.preferCountry]
     * boosts that country's channels in their score, and — for **team sports only** ([demotePpv]) —
     * PPV/event channels sort to the very bottom via [PpvPenalty], applied AFTER the boost so a
     * preferred-country PPV feed never tops a real channel that actually carries the fixture. Duels
     * (tennis/MMA) and events (F1/golf) keep PPV feeds in normal order: those channels ("US TENNIS
     * PPV") ARE the carriers there. The PPV demotion is a sort key only; the match's
     * [ChannelMatch.score] keeps the evidence value.
     */
    private fun applyFilter(
        scored: List<ChannelMatch>,
        options: SportMatchOptions,
        demotePpv: Boolean,
    ): List<ChannelMatch> {
        val limit = options.limit.coerceIn(MinChannelLimit, MaxChannelLimit)
        val country = options.preferCountry?.trim()?.takeIf { it.isNotEmpty() }
        val ranked = scored
            .map { m ->
                val boosted = if (country != null && SportLanguages.isFromCountry(m.channelName, country)) {
                    m.copy(score = m.score + PreferenceBoost)
                } else {
                    m
                }
                val sortScore = boosted.score - if (demotePpv && isPpvChannel(m.channelName)) PpvPenalty else 0
                boosted to sortScore
            }
            .sortedByDescending { it.second }
            .map { it.first }
        if (options.strict) {
            return SportLanguages.filterStrict(ranked, options.languages, options.excludedLanguages).take(limit)
        }
        val afterExclude = SportLanguages.filterStrict(ranked, emptySet(), options.excludedLanguages)
        val kept = SportLanguages.filterStrict(afterExclude, options.languages)
        return (if (kept.size < options.minPreferred) afterExclude else kept).take(limit)
    }

    /** A PPV/event feed — the fixture's dedicated pay-per-view channel, listed last by policy. */
    private fun isPpvChannel(name: String): Boolean =
        SportNameMatch.containsWord(PpvWord, SportNameMatch.normalize(name))

    /** Entities for matched ids, in the matches' order — the zap context for playback. */
    suspend fun channels(ids: List<Long>): List<ChannelEntity> =
        withContext(Dispatchers.IO) {
            ids.mapNotNull { runCatching { channelDao.getById(it) }.getOrNull() }
        }

    // ---- Pass 1: discovery ----------------------------------------------------------------------

    /**
     * The union of two candidate sources, keyed by channel id (insertion order preserved so the
     * later scoring sees a stable, deduplicated set):
     *
     *  - **A. Channel-name discovery** — the original path, now also fed alias/exonym phrases and
     *    the full broadcast name (not just its longest word).
     *  - **B. Guide discovery** — for each team token, the programmes whose **title or description**
     *    names it inside the kickoff window; their `epgChannelId`s resolve to channels via
     *    [ChannelDao.byEpgKeys]. This is the only way a generically named channel
     *    (`IT: SKY SPORT ARENA`, `DE: SKY SPORT TOP EVENT`, `CANAL+ FOOT`, …) is ever found — the
     *    German Sky feeds keep the teams out of the title ("Fußball: England, Premier League") and
     *    name them only in the body, so a title-only probe misses them.
     *
     * **Critical gotcha (cost a verification round on 2026-10-12):** B must NOT be gated on A's
     * candidate cap. Generic team words ("City", "United") make A fill [NameCandidateCap] within a
     * few queries, and an earlier `out.size < CandidateCap` guard then skipped B entirely — so
     * exactly the premium, generically named channels this feature exists to surface stayed hidden,
     * while the fixture only showed the handful of channels whose *own name* spelled the fixture
     * (e.g. `AU (STAN 01) | Coventry City v Newcastle United …`). Verified against a real
     * 57,360-channel Xtream source: the guide named Coventry v Newcastle on ~57 channels that carry
     * no team word in their name. Do not re-add a cap check around B.
     *
     * B covers team sports (both teams) and duels (both athletes plus the tournament fallback).
     * Duels probe the guide with the athletes' names and the tournament; a fixture carrying only
     * a tournament (a TBD quarterfinal) still pulls the tournament's channels in.
     */
    private suspend fun discoverCandidates(
        fixture: Fixture,
        teamSport: Boolean,
        duelSport: Boolean,
        sourceIds: List<Long>,
        from: Long,
        to: Long,
        epgEvidence: MutableMap<String, MutableList<EpgProgrammeEntity>>,
        confirmedBroadcasters: List<String>,
    ): LinkedHashMap<Long, ChannelEntity> {
        val out = LinkedHashMap<Long, ChannelEntity>()

        // A — channel names, bounded by [NameCandidateCap], matched IN MEMORY over one cached scan
        // of the profile's channels instead of ~30 SQL LIKE scans over the whole playlist. Every
        // row is tested once against all queries, so a 50k-channel playlist costs one pass.
        val queries = nameDiscoveryQueries(fixture, confirmedBroadcasters)
        val folded = queries.map { it.lowercase(Locale.ROOT) to SportNameMatch.normalize(it) }
        val rows = scanRows(sourceIds)
        for (row in rows) {
            if (folded.any { (raw, norm) -> row.rawLower.contains(raw) || row.normName.contains(norm) }) {
                out.putIfAbsent(row.channel.id, row.channel)
                if (out.size >= NameCandidateCap) break
            }
        }

        // B — the guide. A programme title naming a team is enough to pull its channel in; the
        // scoring below still decides whether it finally qualifies. Runs regardless of how full A
        // got — see the gotcha above. The returned rows are kept as [epgEvidence], so pass 2
        // scores from memory instead of querying each candidate's programmes.
        if (teamSport || duelSport || !fixture.tournament.isNullOrBlank()) {
            val epgKeys = LinkedHashSet<String>()
            val seen = HashSet<String>()
            fun add(rows: List<EpgProgrammeEntity>) {
                for (programme in rows) {
                    val key = programme.epgChannelId.trim().lowercase(Locale.ROOT)
                    if (key.isEmpty()) continue
                    epgKeys += key
                    // Dedup by content: a row can come back from several probes, and the entity
                    // id is 0 on hand-built rows.
                    val dedup = key + "|" + programme.startMs + "|" + programme.stopMs + "|" + programme.title
                    if (seen.add(dedup)) {
                        val bucket = epgEvidence.getOrPut(key) { ArrayList() }
                        if (bucket.size < EpgEvidencePerChannelCap) bucket.add(programme)
                    }
                }
            }
            // Broad probes (phrases, single words, abbreviations) stay title-only: a single word
            // like "Newcastle" is far too common in guide *bodies*.
            for (token in epgDiscoveryTokens(fixture)) {
                runCatching { epgDao.searchTitles(token, from, to, EpgMatchLimit) }.getOrNull()?.let(::add)
            }
            // Full club names additionally search the description — where a generic-titled foreign
            // feed ("Fußball: England, Premier League") actually names the teams.
            for (phrase in epgDescriptionPhrases(fixture)) {
                runCatching { epgDao.searchGuide(phrase, from, to, EpgMatchLimit) }.getOrNull()?.let(::add)
            }
            if (epgKeys.isNotEmpty()) {
                runCatching { channelDao.byEpgKeys(sourceIds, epgKeys.take(EpgKeyCap).toList()) }
                    .getOrNull()?.forEach { out.putIfAbsent(it.id, it) }
            }
        }
        return out
    }

    // ---- Channel-name scan (one pass over the playlist, cached) --------------------------------

    /** One channel of the cached name scan, pre-folded so each match is two `contains` calls. */
    private data class ScanRow(val channel: ChannelEntity, val rawLower: String, val normName: String)

    @Volatile
    private var scanCache: Pair<List<Long>, List<ScanRow>>? = null
    private var scanCacheAtMs = 0L

    /**
     * All of the profile's channels, folded once, cached per source set for [ScanCacheTtlMs]. A
     * playlist sync takes effect at the next fixture after the TTL; a 50k-channel playlist costs
     * one query (~a few hundred ms) instead of one SQL LIKE scan per name probe.
     */
    private suspend fun scanRows(sourceIds: List<Long>): List<ScanRow> {
        val now = System.currentTimeMillis()
        val cached = scanCache
        if (cached != null && cached.first == sourceIds && now - scanCacheAtMs < ScanCacheTtlMs) {
            return cached.second
        }
        val rows = runCatching { channelDao.allForSources(sourceIds, ScanRowLimit) }
            .getOrDefault(emptyList())
            .map { channel ->
                ScanRow(
                    channel = channel,
                    rawLower = channel.name.lowercase(Locale.ROOT),
                    normName = SportNameMatch.normalize(channel.name),
                )
            }
        scanCache = sourceIds to rows
        scanCacheAtMs = now
        return rows
    }

    /**
     * Raw `LIKE` probes for the channel-name search: for both teams, the display name, the
     * abbreviation and every alias (each as full phrase + stop-word-filtered words), plus each
     * broadcast name and channel hint. Duels contribute both athletes' names (a doubles side
     * "A / B" contributes both players) and the tournament name. See
     * [SportNameMatch.discoveryPhrases] for why these are raw.
     *
     * Home and away are **interleaved** so that if [NameCandidateCap] truncates the sweep, both
     * teams are still represented rather than the home side consuming the whole budget. League
     * hints run before the tournament terms: a hint ("Tennis") names the league's own channels,
     * while a tournament word ("Masters") also matches other sports' channels and would otherwise
     * spend the cap first.
     */
    private fun nameDiscoveryQueries(fixture: Fixture, confirmedBroadcasters: List<String>): List<String> {
        val home = teamNameQueries(fixture.home)
        val away = teamNameQueries(fixture.away)
        val queries = ArrayList<String>(home.size + away.size + fixture.broadcasts.size + fixture.channelHints.size)
        for (i in 0 until maxOf(home.size, away.size)) {
            home.getOrNull(i)?.let(queries::add)
            away.getOrNull(i)?.let(queries::add)
        }
        fixture.duelSides()?.let { sides ->
            val sideA = duelNameQueries(sides.first)
            val sideB = duelNameQueries(sides.second)
            for (i in 0 until maxOf(sideA.size, sideB.size)) {
                sideA.getOrNull(i)?.let(queries::add)
                sideB.getOrNull(i)?.let(queries::add)
            }
        }
        // Hints (a league's on-air phrase) before the tournament: they name the league's channels
        // ("Tennis"), while tournament words ("Masters") also match other sports' channels and would
        // otherwise consume [NameCandidateCap] first.
        for (hint in fixture.channelHints) {
            val trimmed = hint.trim()
            if (trimmed.length >= MinHintLength) {
                queries += SportNameMatch.discoveryPhrases(trimmed, vocab.broadcastStopwords)
            }
        }
        fixture.tournament?.let { queries += SportNameMatch.discoveryPhrases(it, vocab.broadcastStopwords) }
        for (broadcast in fixture.broadcasts) {
            queries += SportNameMatch.discoveryPhrases(broadcast, vocab.broadcastStopwords)
        }
        // TV-guide-confirmed broadcasters ("Sky Sports Premier League") — full phrases only.
        for (confirmed in confirmedBroadcasters) {
            queries += SportNameMatch.discoveryPhrases(confirmed, vocab.broadcastStopwords)
        }
        return queries.distinct()
    }

    /** Raw probes for one team: display name, abbreviation and every known alias/exonym. */
    private fun teamNameQueries(team: SportTeam?): List<String> {
        if (team == null) return emptyList()
        val queries = ArrayList<String>()
        queries += SportNameMatch.discoveryPhrases(team.name, vocab.teamStopwords)
        queries += SportNameMatch.discoveryPhrases(team.abbreviation, vocab.teamStopwords)
        for (alias in vocab.aliasesFor(listOf(team.name, team.abbreviation))) {
            queries += SportNameMatch.discoveryPhrases(alias, vocab.teamStopwords)
        }
        return queries
    }

    /**
     * Raw probes for one duel side: each player name (a doubles side "A / B" yields both) as a
     * full phrase plus its stop-word-filtered words. Placeholders ("TBD") yield nothing.
     */
    private fun duelNameQueries(side: List<String>): List<String> {
        val queries = ArrayList<String>()
        for (name in side) {
            queries += SportNameMatch.discoveryPhrases(name, vocab.teamStopwords)
        }
        return queries
    }

    /**
     * Raw `LIKE` probes against guide **titles**: the team tokens (name, abbreviation, aliases),
     * the duel athletes' names, the tournament words and any channel hint. Broadcasts are
     * deliberately excluded — a guide entry naming a broadcaster is rare and would only add noise.
     * Capped by [EpgTokenCap]. See [epgDescriptionPhrases] for the narrower, multi-word probe that
     * also reads descriptions.
     */
    private fun epgDiscoveryTokens(fixture: Fixture): List<String> {
        val tokens = LinkedHashSet<String>()
        tokens += teamNameQueries(fixture.home)
        tokens += teamNameQueries(fixture.away)
        fixture.duelSides()?.let { sides ->
            tokens += duelNameQueries(sides.first)
            tokens += duelNameQueries(sides.second)
        }
        fixture.tournament?.let { tokens += SportNameMatch.discoveryPhrases(it, vocab.broadcastStopwords) }
        for (hint in fixture.channelHints) {
            val trimmed = hint.trim()
            if (trimmed.length >= MinHintLength) tokens += trimmed
        }
        return tokens.filter { it.length >= MinTokenLength }.take(EpgTokenCap)
    }

    /**
     * The full, multi-word club names (and any multi-word alias) to probe against guide
     * **descriptions**. Deliberately narrower than [epgDiscoveryTokens]: a guide body is prose, so a
     * single word ("Newcastle", "City") matches a soap's description or an unrelated club, while the
     * full name ("Newcastle United", "Coventry City") is what a foreign-language entry actually
     * spells out. Single-word teams still reach titles through [epgDiscoveryTokens].
     * Duel athletes contribute their full multi-word names ("Alex de Minaur") the same way.
     */
    private fun epgDescriptionPhrases(fixture: Fixture): List<String> {
        val phrases = LinkedHashSet<String>()
        for (team in listOfNotNull(fixture.home, fixture.away)) {
            team.name.trim().takeIf { it.length >= MinTokenLength && it.contains(' ') }?.let(phrases::add)
            for (alias in vocab.aliasesFor(listOf(team.name, team.abbreviation))) {
                alias.trim().takeIf { it.length >= MinTokenLength && it.contains(' ') }?.let(phrases::add)
            }
        }
        fixture.duelSides()?.let { sides ->
            for (side in listOf(sides.first, sides.second)) {
                for (name in side) {
                    name.trim().takeIf { it.length >= MinTokenLength && it.contains(' ') }?.let(phrases::add)
                }
            }
        }
        fixture.tournament?.trim()?.takeIf { it.length >= MinTokenLength && it.contains(' ') }?.let(phrases::add)
        return phrases.take(EpgTokenCap)
    }

    /** Scoring tokens for one team: its names, abbreviation and aliases, normalized and folded. */
    private fun SportTeam.scoringTokens(): Set<String> {
        val tokens = LinkedHashSet<String>()
        tokens += SportNameMatch.tokensForName(name, vocab.teamStopwords)
        if (abbreviation.length >= MinTokenLength) {
            tokens += SportNameMatch.tokensForName(abbreviation, vocab.teamStopwords)
        }
        for (alias in vocab.aliasesFor(listOf(name, abbreviation))) {
            tokens += SportNameMatch.tokensForName(alias, vocab.teamStopwords)
        }
        return tokens
    }

    /** Scoring tokens for one duel side: each player's normalized words (placeholders excluded). */
    private fun List<String>.sideTokens(): Set<String> {
        val tokens = LinkedHashSet<String>()
        for (name in this) {
            tokens += SportNameMatch.tokensForName(name, vocab.teamStopwords)
        }
        return tokens
    }

    /** Scoring tokens for the tournament name, or empty when the fixture carries none. */
    private fun Fixture.tournamentTokens(): Set<String> {
        val tournament = tournament?.trim().orEmpty()
        if (tournament.isEmpty()) return emptySet()
        return SportNameMatch.tokensForName(tournament, vocab.broadcastStopwords)
    }

    // ---- Pass 2: scoring ------------------------------------------------------------------------

    /**
     * Scores one candidate, Lume-style. A team candidate only qualifies on evidence that **both**
     * teams are present — in the channel name, or together in one EPG programme. A programme that
     * names a single team (a news ticker, a soap set in the club's city, a movie blurb) is not the
     * fixture and scores nothing. A duel candidate qualifies on both athletes the same way, and —
     * for recall — on one athlete together with tennis context or on the tournament alone (see the
     * class KDoc). Every EPG programme must also **cover the kickoff** ([coversKickoff]) — a
     * same-name listing at another hour is a replay or a preview, not this fixture.
     *
     * [programmes] are the channel's guide rows collected during discovery, so this function issues
     * no queries. Returns a [ChannelMatch] only when the score is positive.
     */
    private fun scoreChannel(
        channel: ChannelEntity,
        fixture: Fixture,
        home: SportTeam?,
        away: SportTeam?,
        homeTokens: Set<String>,
        awayTokens: Set<String>,
        sideATokens: Set<String>,
        sideBTokens: Set<String>,
        tournamentTokens: Set<String>,
        programmes: List<EpgProgrammeEntity>,
        confirmedBroadcasters: List<String>,
    ): ChannelMatch? {
        var score = 0
        var epgTitle: String? = null
        var exact = false
        val name = channel.name.lowercase(Locale.ROOT)
        val nameNorm = SportNameMatch.normalize(channel.name)
        // Youth firewall (symmetric): the marker decides, not the teams. A generic channel name
        // without a marker still reaches either side through its guide line below.
        val fixtureYouth = fixture.isYouthFixture()
        val channelYouth = isYouthText(channel.name)

        // --- Channel-name tier (Lume `.channelName`): the name itself names BOTH teams ---
        // ("DAZN 5 | Bayern vs Dortmund"). A one-team name is not enough.
        if (home != null && away != null &&
            fixtureYouth == channelYouth &&
            homeTokens.any { SportNameMatch.containsWord(it, nameNorm) } &&
            awayTokens.any { SportNameMatch.containsWord(it, nameNorm) }
        ) {
            score += NameBothTeamsScore
            exact = true
        }
        // A duel channel names both athletes ("Khachanov vs De Minaur"); one athlete is not enough.
        if (sideATokens.isNotEmpty() && sideBTokens.isNotEmpty() &&
            fixtureYouth == channelYouth &&
            sideATokens.any { SportNameMatch.containsWord(it, nameNorm) } &&
            sideBTokens.any { SportNameMatch.containsWord(it, nameNorm) }
        ) {
            score += NameBothTeamsScore
            exact = true
        }
        // Broadcast names (ESPN) and a league's extra on-air phrase ("Sky Sports Main Event" for F1).
        // The word fallback applies only to SINGLE-WORD broadcasts ("ESPN" → "ESPN 2"): splitting a
        // multi-word broadcast into words ("NBA League Pass" → "league", "USA Network" → "network")
        // matches unrelated channels ("UK: NATIONAL LEAGUE"), so a multi-word broadcast must match
        // as a whole phrase or not at all.
        if (fixture.broadcasts.any { SportNameMatch.containsPhrase(it, nameNorm) }) {
            score += BroadcastPhraseScore
        } else if (fixture.broadcasts.any { broadcast ->
                val word = broadcast.words().singleOrNull() ?: return@any false
                word.length >= MinHintLength &&
                    SportNameMatch.normalize(word) !in vocab.broadcastStopwords &&
                    SportNameMatch.containsWord(word, nameNorm)
            }
        ) {
            score += BroadcastWordScore
        }
        if (fixture.channelHints.any { it.isNotBlank() && name.contains(it.lowercase(Locale.ROOT)) }) {
            score += HintScore
        }
        // TV-guide-confirmed broadcasters: the guide names this channel as the carrier, which
        // ESPN's own broadcast field never would. Phrase-only (a multi-word broadcaster name is
        // one unit); below direct EPG evidence (+10) but above every name-only signal.
        if (confirmedBroadcasters.any { SportNameMatch.containsPhrase(it, nameNorm) }) {
            score += ConfirmedBroadcasterScore
        }

        // --- EPG tier (Lume): a programme counts only when it names BOTH teams AND covers kickoff ---
        if (programmes.isNotEmpty() && homeTokens.isNotEmpty() && awayTokens.isNotEmpty()) {
            // Short abbreviations ("NEW", "COV") are far too noisy against guide prose ("New Girl",
            // "Covenant"), so the EPG match uses only tokens of [EpgMinTokenLength] or more.
            val epgHomeTokens = homeTokens.filter { it.length >= EpgMinTokenLength }
            val epgAwayTokens = awayTokens.filter { it.length >= EpgMinTokenLength }
            var best = 0
            var bestTitle: String? = null
            for (programme in programmes) {
                // The programme must actually be on at kickoff — a same-name listing at another
                // hour (a replay later that evening) is not this fixture.
                if (!coversKickoff(programme, fixture.dateMs)) continue
                val titleNorm = SportNameMatch.normalize(programme.title)
                val descriptionNorm = SportNameMatch.normalize(
                    programme.description?.take(EpgDescriptionScanChars).orEmpty(),
                )
                // Youth firewall (symmetric): a youth line never scores for a senior fixture
                // and vice versa — checked on the full text, not the truncated scoring slice.
                if (isYouthText(programme.title + " " + programme.description.orEmpty()) != fixtureYouth) continue
                val homeInTitle = epgHomeTokens.any { SportNameMatch.containsWord(it, titleNorm) }
                val awayInTitle = epgAwayTokens.any { SportNameMatch.containsWord(it, titleNorm) }
                val homeAnywhere = homeInTitle ||
                    epgHomeTokens.any { SportNameMatch.containsWord(it, descriptionNorm) }
                val awayAnywhere = awayInTitle ||
                    epgAwayTokens.any { SportNameMatch.containsWord(it, descriptionNorm) }
                // Lume: only a programme naming BOTH teams is this fixture. "Coventry" or "City" or
                // "United" alone is a news ticker, a soap or a movie blurb, not the match.
                if (!(homeAnywhere && awayAnywhere)) continue

                // Both in the title is the fixture line — the strongest EPG signal; both named only
                // across title/description is the weaker "the body lists the games" case.
                var programmeScore = if (homeInTitle && awayInTitle) {
                    EpgBothTeamsScore
                } else {
                    EpgDescriptionBothScore
                }
                val categories = programme.categories
                if (categories != null && SportNameMatch.mentionsSport(categories, vocab.sportWords)) {
                    programmeScore += SportCategoryScore
                }
                if (programmeScore > best) {
                    best = programmeScore
                    bestTitle = programme.title
                }
            }
            if (best > 0) {
                score += best
                epgTitle = bestTitle
            }
        }

        // --- EPG duel/tournament tier: the same both-sides rule for the two athletes, then the two
        // recall tiers (one athlete with tennis context, tournament alone) below it ---
        if (programmes.isNotEmpty() &&
            (sideATokens.isNotEmpty() || sideBTokens.isNotEmpty() || tournamentTokens.isNotEmpty())
        ) {
            val epgSideATokens = sideATokens.filter { it.length >= EpgMinTokenLength }
            val epgSideBTokens = sideBTokens.filter { it.length >= EpgMinTokenLength }
            val epgTournamentTokens = tournamentTokens.filter { it.length >= EpgMinTokenLength }
            var best = 0
            var bestTitle: String? = null
            for (programme in programmes) {
                if (!coversKickoff(programme, fixture.dateMs)) continue
                val titleNorm = SportNameMatch.normalize(programme.title)
                val descriptionNorm = SportNameMatch.normalize(
                    programme.description?.take(EpgDescriptionScanChars).orEmpty(),
                )
                // Youth firewall (symmetric), same as the team tier above.
                if (isYouthText(programme.title + " " + programme.description.orEmpty()) != fixtureYouth) continue
                fun anyIn(tokens: List<String>): Boolean =
                    tokens.any { SportNameMatch.containsWord(it, titleNorm) } ||
                        tokens.any { SportNameMatch.containsWord(it, descriptionNorm) }
                val sideAInTitle = epgSideATokens.any { SportNameMatch.containsWord(it, titleNorm) }
                val sideBInTitle = epgSideBTokens.any { SportNameMatch.containsWord(it, titleNorm) }
                val sideAAnywhere = sideAInTitle ||
                    epgSideATokens.any { SportNameMatch.containsWord(it, descriptionNorm) }
                val sideBAnywhere = sideBInTitle ||
                    epgSideBTokens.any { SportNameMatch.containsWord(it, descriptionNorm) }
                val tournamentAnywhere = epgTournamentTokens.isNotEmpty() && anyIn(epgTournamentTokens)
                val programmeScore = when {
                    // Both athletes in one programme — the duel line, strongest duel signal.
                    epgSideATokens.isNotEmpty() && epgSideBTokens.isNotEmpty() &&
                        sideAAnywhere && sideBAnywhere -> {
                        var s = if (sideAInTitle && sideBInTitle) {
                            EpgBothTeamsScore
                        } else {
                            EpgDescriptionBothScore
                        }
                        val categories = programme.categories
                        if (categories != null && SportNameMatch.mentionsSport(categories, vocab.sportWords)) {
                            s += SportCategoryScore
                        }
                        s
                    }
                    // One athlete plus tennis context in the same programme — a session or preview
                    // naming one of the two ("Zverev" inside "Tennis: ATP Shanghai").
                    (sideAAnywhere xor sideBAnywhere) && programme.hasTennisContext(
                        titleNorm,
                        descriptionNorm,
                        epgTournamentTokens,
                    ) -> EpgSingleWithContextScore
                    // Only the tournament ("Tennis: Shanghai Masters") — the weakest signal, but
                    // better than an empty pane.
                    tournamentAnywhere -> EpgTournamentScore
                    else -> 0
                }
                if (programmeScore > best) {
                    best = programmeScore
                    bestTitle = programme.title
                }
            }
            if (best > 0) {
                score += best
                // A duel/tournament title only becomes the shown line when no stronger team line won.
                if (epgTitle == null) epgTitle = bestTitle
            }
        }

        // --- Region tier (US fixtures): a US-tagged feed sorts above equal foreign evidence ---
        // College/NFL/… games are US broadcasts; a `US:` feed carrying the same game as a CA/AU/UK
        // feed is the home feed and wins the tie. Deliberately bonus-only, never a filter or malus:
        // without a US feed the foreign entries keep the pane non-empty. Applied only to an already
        // qualifying candidate (`score > 0`), so the bonus can never conjure a match from nothing.
        // Runs inside pass 2, hence inside the language filter's input — the filter-before-cap order
        // at the end of `match` is untouched.
        if (score > 0 && fixture.isUsFixture() &&
            SportLanguages.countryCodeOf(channel.name) == UsCountryCode
        ) {
            score += UsRegionBonus
        }

        return if (score > 0) {
            ChannelMatch(
                channelId = channel.id,
                channelName = channel.name,
                logoUrl = channel.logoUrl,
                epgTitle = epgTitle,
                score = score,
                isExact = exact,
            )
        } else {
            null
        }
    }

    /** The programme must actually be on at kickoff — a same-name listing at another hour is not it. */
    private fun coversKickoff(programme: EpgProgrammeEntity, dateMs: Long): Boolean =
        programme.startMs <= dateMs && programme.stopMs >= dateMs

    /**
     * Whether a programme carries tennis context for the single-athlete recall tier: the tennis
     * words in title/description, one of the fixture's tournament tokens, or a sport category.
     */
    private fun EpgProgrammeEntity.hasTennisContext(
        titleNorm: String,
        descriptionNorm: String,
        tournamentTokens: List<String>,
    ): Boolean {
        if (tournamentTokens.any {
                SportNameMatch.containsWord(it, titleNorm) ||
                    SportNameMatch.containsWord(it, descriptionNorm)
            }
        ) {
            return true
        }
        if (TennisWords.any {
                SportNameMatch.containsWord(it, titleNorm) ||
                    SportNameMatch.containsWord(it, descriptionNorm)
            }
        ) {
            return true
        }
        val categories = categories
        return categories != null && SportNameMatch.mentionsSport(categories, vocab.sportWords)
    }

    // ---- Helpers --------------------------------------------------------------------------------

    private fun String.words(): List<String> = trim().split(WordSplit).filter { it.isNotBlank() }

    /**
     * The two duel sides of a fixture (tennis singles, MMA): each side's player names. A doubles
     * side "A / B" yields both players. Null unless the fixture is a two-entry `VERSUS` duel
     * without home/away — leaderboards and EVENTs never qualify. Placeholder sides ("TBD") are
     * dropped, so a side may come back empty (a TBD quarterfinal keeps its tournament fallback).
     */
    private fun Fixture.duelSides(): Pair<List<String>, List<String>>? {
        if (home != null || away != null) return null
        if (format != FixtureFormat.VERSUS || entries.size != 2) return null
        return Pair(entries[0].name.playerNames(), entries[1].name.playerNames())
    }

    /** Split a duel entry into player names ("Cash / Glasspool" → both); drops placeholders. */
    private fun String.playerNames(): List<String> =
        split(RosterSplit).map { it.trim() }
            .filter { it.isNotEmpty() && !it.isPlaceholderName() }

    /** ESPN marks unknown future opponents "TBD" (often with a negative competitor id). */
    private fun String.isPlaceholderName(): Boolean = trim().equals(PlaceholderName, ignoreCase = true)

    /**
     * Whether a fixture is a youth game. Checked on the league id plus the tournament/event title
     * (e.g. a `uefa.youth` league or a "Youth League" tournament); team names are deliberately
     * excluded — a senior side is never youth-marked by its own name.
     */
    private fun Fixture.isYouthFixture(): Boolean =
        isYouthText(leagueId) || isYouthText(tournament.orEmpty()) || isYouthText(title.orEmpty())

    /**
     * Whether a text carries a youth marker (`youth`, `jugend` incl. compounds like `Jugendliga`,
     * or an age grade `u17–u23` in compact or hyphenated form). Checked on the normalized form via
     * [SportNameMatch], so casing/accents never decide; the hyphenated `u-19` normalizes to
     * `u 19` and is matched with an optional space.
     */
    private fun isYouthText(text: String): Boolean {
        if (text.isBlank()) return false
        val norm = SportNameMatch.normalize(text)
        if (norm.contains(YouthWord) || norm.contains(JugendWord)) return true
        return YouthAgePattern.containsMatchIn(norm)
    }

    /**
     * Whether a fixture is a US-league game (NFL/college football, NBA, MLB, NHL, …) whose home
     * feeds carry a `US:` tag. Matched on the ESPN league id: any `college` league plus the Big-4
     * sports count; `basketball.euroleague` is explicitly excluded so European basketball never
     * prefers US feeds.
     */
    private fun Fixture.isUsFixture(): Boolean {
        if ("college" in leagueId) return true
        if (leagueId.startsWith("football.") || leagueId.startsWith("baseball.") ||
            leagueId.startsWith("hockey.")
        ) {
            return true
        }
        return leagueId.startsWith("basketball.") && leagueId != EuroleagueId
    }

    companion object {
        /** How many channels one fixture shows (user-settable, see [SportMatchOptions.limit]). */
        const val DefaultChannelLimit = 12
        /** Smallest list a fixture shows — below this the language quota could never be met. */
        const val MinChannelLimit = 5
        /** Largest list a fixture shows — bounds the TV pane and the preview zap context. */
        const val MaxChannelLimit = 50
        /** Preferred-language hits for a list to count as covered (strict mode never pads up to it). */
        const val MinPreferredCount = 5
        /**
         * Score added to a fixture's channels from the preferred country (see
         * [SportMatchOptions.preferCountry]). Above any natural score (the highest signal, a guide
         * title carrying both teams, is [EpgBothTeamsScore] and the signals add up to ~25), so the
         * preferred broadcaster always sorts first — while every other channel stays listed.
         */
        const val PreferenceBoost = 50
        /**
         * A PPV/event feed — the fixture's dedicated pay-per-view channel — is pushed to the very
         * bottom by policy (real channels that carry the fixture come first), even when it sits in
         * the preferred country. Applied in [applyFilter] AFTER the preference boost, so the
         * penalty wins every time.
         */
        private const val PpvPenalty = 100
        /** The channel-name word that marks a PPV feed (normalized matching). */
        private const val PpvWord = "ppv"
        /**
         * Cap on channels discovered **by name**, across all name queries (was 60, then 120). The
         * guide discovery is deliberately NOT bounded by this — see `discoverCandidates`.
         */
        private const val NameCandidateCap = 120
        /** Per-token cap for a guide-title probe. */
        private const val EpgMatchLimit = 40
        /** How many tokens are probed against the guide. */
        private const val EpgTokenCap = 24
        /** How many guide channels at most resolve to candidates (bounds the scoring set). */
        private const val EpgKeyCap = 400
        /** How many guide rows are kept per channel as scoring evidence. */
        private const val EpgEvidencePerChannelCap = 40
        /** How much of a programme description is searched (a conference fronts its games). */
        private const val EpgDescriptionScanChars = 400
        /** Only team/broadcast/hint phrases at least this long are probed. */
        private const val MinHintLength = 4
        /** Shortest token kept by the tokenizer. */
        private const val MinTokenLength = 3
        /** Shortest token allowed against guide prose; below this an abbreviation is too ambiguous. */
        private const val EpgMinTokenLength = 4
        /** How many channels the cached name scan reads — above any real playlist's size. */
        private const val ScanRowLimit = 100_000
        /** How long the cached name scan survives; a resync shows up at the next fixture after it. */
        private const val ScanCacheTtlMs = 30_000L

        /** How far before kickoff a programme may be the pre-match build-up. */
        private const val EpgBeforeMs = 2L * 60L * 60L * 1000L
        /** How far after kickoff a programme may still start and count as this fixture. */
        private const val EpgAfterMs = 4L * 60L * 60L * 1000L

        // Score weights (Lume-aligned 2026-10-12). A candidate qualifies only on evidence that both
        // teams are present: `NameBothTeamsScore` (the channel name names both) or an EPG programme
        // that names both (`EpgBothTeamsScore` in the title, `EpgDescriptionBothScore` otherwise).
        // One-team hits score nothing — that is what keeps news/entertainment channels out.
        // Duels add two weaker recall tiers below the both-athletes rule: one athlete together with
        // tennis context (`EpgSingleWithContextScore`) and the tournament alone (`EpgTournamentScore`).
        private const val NameBothTeamsScore = 4
        private const val BroadcastWordScore = 3
        private const val BroadcastPhraseScore = 3
        private const val HintScore = 6
        private const val EpgBothTeamsScore = 10
        private const val EpgDescriptionBothScore = 3
        private const val EpgSingleWithContextScore = 2
        private const val EpgTournamentScore = 1
        private const val SportCategoryScore = 1
        /** A TV-guide-confirmed carrier ("Sky Sports Premier League") — below EPG evidence, above names. */
        private const val ConfirmedBroadcasterScore = 9
        /**
         * Region tie-break for US-league games: a `US:`-tagged feed outranks an equally evidenced
         * foreign feed, but never outranks genuinely stronger evidence (a +10 title hit still beats
         * a +3 description hit with the bonus). Kept small for exactly that reason.
         */
        private const val UsRegionBonus = 2
        /** Country tag that marks the home feed of a US-league game (see `SportLanguages`). */
        private const val UsCountryCode = "US"
        /** European basketball shares the `basketball.` prefix but is not a US league. */
        private const val EuroleagueId = "basketball.euroleague"

        /** Words that give a programme tennis context for the single-athlete recall tier. */
        private val TennisWords = setOf("tennis", "atp", "wta")

        /** Substrings (on normalized text) marking a youth line; compounds included. */
        private const val YouthWord = "youth"
        private const val JugendWord = "jugend"

        /**
         * Age grades on normalized text: compact (`u19`) or hyphenated (`u-19` → `u 19`).
         * Word-bounded, so `eu23` never matches.
         */
        private val YouthAgePattern = Regex("\\bu\\s?(17|18|19|20|21|23)\\b")

        /** A doubles roster side separator ("Cash / Glasspool"). */
        private val RosterSplit = Regex("\\s*/\\s*")

        /** ESPN's placeholder for an unknown future opponent. */
        private const val PlaceholderName = "TBD"

        private val WordSplit = Regex("\\s+")
    }
}
