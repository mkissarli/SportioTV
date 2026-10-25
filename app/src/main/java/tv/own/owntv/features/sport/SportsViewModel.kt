@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class, kotlinx.coroutines.FlowPreview::class) // flatMapLatest + debounce

package tv.own.owntv.features.sport

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.supervisorScope
import tv.own.owntv.core.companion.CompanionController
import tv.own.owntv.core.companion.CompanionSelectItem
import tv.own.owntv.core.companion.CompanionServerState
import tv.own.owntv.core.repository.SourceRepository
import tv.own.owntv.core.settings.SettingsRepository

/**
 * Sports hub state: ESPN boards per league (cached 15 minutes), the active-league set, follow sets,
 * the selected league and fixture, channel matches and standings.
 *
 * Only **active** leagues (the user's selection, default = [DefaultActiveLeagueIds]) are loaded, so
 * the full [SportsLeagues] catalogue can be large without costing a request per league. Playback
 * itself stays in `LiveViewModel` — the screen calls it with a matched channel id, so there is
 * exactly one zap path.
 */
class SportsViewModel(
    private val prefs: SportsPrefs,
    private val api: EspnApi,
    private val f1Api: F1ScheduleApi,
    private val guideApi: BroadcastGuideApi,
    private val matcher: SportsMatcher,
    private val aiClient: DeepSeekClient,
    private val aiMatcher: AiChannelMatcher,
    private val aiPlaylistAnalyst: AiPlaylistAnalyst,
    private val aiBoardMatcher: AiBoardMatcher,
    private val priors: SportsPriors,
    private val companion: CompanionController,
    private val settings: SettingsRepository,
    private val sourceRepository: SourceRepository,
) : ViewModel() {

    sealed interface BoardState {
        data object Loading : BoardState
        data class Content(val board: EspnApi.Scoreboard) : BoardState
        data object Error : BoardState
    }

    sealed interface MatchesState {
        data object Idle : MatchesState
        data object Loading : MatchesState
        data class Content(val matches: List<ChannelMatch>) : MatchesState
    }

    /** The AI recommendation state for the currently selected fixture. */
    sealed interface AiState {
        data object Idle : AiState
        data object Loading : AiState
        data class Content(
            val suggestions: List<AiSuggestion>,
            val countries: Set<AiCountry> = emptySet(),
        ) : AiState
        data class Error(val failure: AiFailure) : AiState
    }

    /** The per-fixture full-playlist AI action's state (opt-in; never runs on its own). */
    sealed interface PlaylistAiState {
        data object Idle : PlaylistAiState
        data object Loading : PlaylistAiState
        data class Content(
            /** Verified playlist rows added/confirmed by the accepted answer. */
            val added: Int,
            /** How many playlist entries the request(s) covered. */
            val checked: Int,
        ) : PlaylistAiState
        data class Error(val failure: AiFailure) : PlaylistAiState
    }

    /** The second column's filter for the selected league. */
    enum class FixtureTab { LIVE, TODAY, UPCOMING, PAST }

    private data class BoardAiInput(
        val enabled: Boolean,
        val apiKey: String,
        val countries: Set<String>,
        val market: String?,
        val guideVersion: Int,
        val generation: Int,
    )

    data class LeagueUi(
        val league: SportLeague,
        val liveCount: Int,
        val loading: Boolean,
    )

    /** Leagues the user activated (shown in the rail and loaded); default = the brand's starter set. */
    val activeLeagues: StateFlow<Set<String>> = prefs.followedLeagues
        .map { ids -> ids.filterTo(LinkedHashSet()) { sportsLeagueById(it) != null } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), DefaultActiveLeagueIds)

    val followedTeams: StateFlow<Set<String>> = prefs.followedTeams
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptySet())

    /** Followed individual athletes (tennis/MMA duel entries) — never racing/golf field entries. */
    val followedPlayers: StateFlow<Set<String>> = prefs.followedPlayers
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptySet())

    val hubVisible: StateFlow<Boolean> = prefs.hubVisible
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), true)

    /** Languages the matched channels are filtered to; empty = all. Default: German. */
    val preferredLanguages: StateFlow<Set<SportLanguage>> = prefs.preferredLanguages
        .map { names -> names.mapNotNull { runCatching { SportLanguage.valueOf(it) }.getOrNull() }.toSet() }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SportLanguages.default)

    /** Country whose channels rank first ("" = none); see [SportsPrefs.preferredCountry]. */
    val preferredCountry: StateFlow<String> = prefs.preferredCountry
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SportsPrefs.DefaultPreferredCountry)

    /** How many channels one fixture lists (5–50, default 20); changes apply without restart. */
    val channelLimit: StateFlow<Int> = prefs.channelLimit
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SportsMatcher.DefaultChannelLimit)

    /** Languages never shown in a fixture's channel list — a hard "never", even for exact hits. */
    val excludedLanguages: StateFlow<Set<SportLanguage>> = prefs.excludedLanguages
        .map { names -> names.mapNotNull { runCatching { SportLanguage.valueOf(it) }.getOrNull() }.toSet() }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptySet())

    /** True unless the user switched the language filter to its legacy never-empty fallback. */
    val languageFilterStrict: StateFlow<Boolean> = prefs.languageFilterMode
        .map { it != SportsPrefs.FilterModeLegacy }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), true)

    /** True once the language prompt was answered (or dismissed); it is shown at most once. */
    val languageAsked: StateFlow<Boolean> = prefs.languageAsked
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    /** DeepSeek API key; empty means the AI recommendation is not configured yet. */
    val deepSeekApiKey: StateFlow<String> = prefs.deepSeekApiKey
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), "")

    /** How many AI suggestions one request lists (5–50, default 20). */
    val aiSuggestionLimit: StateFlow<Int> = prefs.aiSuggestionLimit
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SportsMatcher.DefaultChannelLimit)

    /**
     * Whether the AI functions may run at all (Sport hub recommendation, AI reading of a pasted
     * provider message). Off by default; the switch sits in the App settings.
     *
     * Eagerly collected: the flag decides whether a request may leave the device, and it must
     * never be read as "off" just because nobody is looking at it.
     */
    val aiEnabled: StateFlow<Boolean> = prefs.aiEnabled
        .stateIn(viewModelScope, SharingStarted.Eagerly, true)

    /** Automatic board-level AI matching (default on; still needs [aiEnabled] and a key). */
    val aiSportsMatching: StateFlow<Boolean> = prefs.aiSportsMatching
        .stateIn(viewModelScope, SharingStarted.Eagerly, true)

    /** AI-confirmed channel counts per fixture id, filled asynchronously for the cards. */
    private val _aiBoardHits = MutableStateFlow<Map<String, Int>>(emptyMap())
    val aiBoardHits: StateFlow<Map<String, Int>> = _aiBoardHits.asStateFlow()

    /** Last countries picked in the AI dialog, in catalogue order. */
    val aiCountries: StateFlow<Set<AiCountry>> = prefs.aiCountries
        .map { codes ->
            val selected = codes.mapNotNull { AiCountry.fromCodeOrName(it) }.toSet()
            selected.ifEmpty { setOf(AiCountry.GERMANY) }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), setOf(AiCountry.GERMANY))

    /** Leagues that hold a followed team or athlete — loaded too, so Favourites can show them. */
    private val followedItemLeagues: StateFlow<Set<String>> = combine(followedTeams, followedPlayers) { teams, players ->
        (teams + players).mapNotNull { it.substringBefore(TeamKeySep).takeIf { id -> sportsLeagueById(id) != null } }.toSet()
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptySet())

    /** Active leagues plus the ones a followed team/athlete is in — the boards we fetch. */
    private val loadingLeagues: StateFlow<Set<String>> =
        combine(activeLeagues, followedItemLeagues) { active, followed -> active + followed }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), DefaultActiveLeagueIds)

    private val boards = MutableStateFlow<Map<String, BoardState>>(emptyMap())
    private val boardAt = HashMap<String, Long>()
    private val standingsCache = HashMap<String, List<LeagueTable>>()
    private val matchesCache = HashMap<String, List<ChannelMatch>>()
    /** Drops a late match result after a fixture/mode/option change. */
    private var matchesRequest = 0

    private val _selectedLeagueId = MutableStateFlow(FavouritesId)
    val selectedLeagueId: StateFlow<String> = _selectedLeagueId.asStateFlow()

    /** Selected sport coin ([SportAllId] = every sport); sports only filter the aggregate view. */
    private val _selectedSportId = MutableStateFlow(SportAllId)
    val selectedSportId: StateFlow<String> = _selectedSportId.asStateFlow()

    private val _selectedFixture = MutableStateFlow<Fixture?>(null)
    val selectedFixture: StateFlow<Fixture?> = _selectedFixture.asStateFlow()

    /**
     * The TV guide's own carriers for the selected fixture ("Sky Sports Main Event"), from the last
     * scrape. Shown above the channel list so it is obvious what the guide said; re-read when a new
     * snapshot lands.
     */
    val guideBroadcasters: StateFlow<List<String>> = combine(_selectedFixture, guideApi.version) { fixture, _ ->
        fixture?.let { guideApi.confirmedBroadcasters(it) }.orEmpty()
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** Which bucket of the selected league's fixtures is shown (Live / Upcoming / Past). */
    private val _tab = MutableStateFlow(FixtureTab.UPCOMING)
    val tab: StateFlow<FixtureTab> = _tab.asStateFlow()

    /** True once the user picks a tab by hand; the league default then stops overriding it. */
    private var userChoseTab = false

    /** The selected league's board (null until first load), for fixtures + error state. */
    val selectedBoard: StateFlow<BoardState?> =
        combine(boards, _selectedLeagueId) { current, id -> current[id] }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    private val _matches = MutableStateFlow<MatchesState>(MatchesState.Idle)
    val matches: StateFlow<MatchesState> = _matches.asStateFlow()

    private val _aiState = MutableStateFlow<AiState>(AiState.Idle)
    val aiState: StateFlow<AiState> = _aiState.asStateFlow()

    private val _playlistAiState = MutableStateFlow<PlaylistAiState>(PlaylistAiState.Idle)
    val playlistAiState: StateFlow<PlaylistAiState> = _playlistAiState.asStateFlow()

    /** Which channel list the detail pane shows: false = classic matcher, true = AI suggestions. */
    private val _aiListSelected = MutableStateFlow(false)
    val aiListSelected: StateFlow<Boolean> = _aiListSelected.asStateFlow()

    /**
     * The classic column's evidence set: false = original guide/EPG matcher only, true = the same
     * matcher plus guide carriers, priors and accepted AI rows. Selecting the AI column leaves this
     * untouched, so returning to a classic column restores its last choice.
     */
    private val _classicAiSelected = MutableStateFlow(false)
    val classicAiSelected: StateFlow<Boolean> = _classicAiSelected.asStateFlow()

    private var aiRequest = 0
    private var aiJob: kotlinx.coroutines.Job? = null

    private var playlistAiRequest = 0
    private var playlistAiJob: kotlinx.coroutines.Job? = null

    /**
     * Accepted full-playlist answers by fixture id. The analyst's own cache also keys every input,
     * so a changed playlist/guide/prior fingerprint causes a fresh request; this map only feeds the
     * matcher's merge and keeps the pane stable across re-selections.
     */
    private val aiPlaylistByFixture = HashMap<String, AiPlaylistResult>()

    /** Accepted board-level AI rows by fixture id; merged into the matcher like any other evidence. */
    private val aiBoardByFixture = HashMap<String, List<ChannelMatch>>()
    private var aiBoardToken = 0
    private val aiBoardGeneration = MutableStateFlow(0)

    /**
     * Completed answers, keyed by fixture id and by request (fixture + countries). Returning from
     * playback or another fixture restores the suggestion list **without** a second paid API call.
     */
    private val aiByFixture = HashMap<String, AiState.Content>()
    private val aiByRequest = HashMap<String, AiState.Content>()

    private val _standings = MutableStateFlow<List<LeagueTable>>(emptyList())
    val standings: StateFlow<List<LeagueTable>> = _standings.asStateFlow()

    private val _standingsLoading = MutableStateFlow(false)
    val standingsLoading: StateFlow<Boolean> = _standingsLoading.asStateFlow()

    /** The active leagues in catalogue order, with their live counts — the rail. */
    val leagues: StateFlow<List<LeagueUi>> =
        combine(boards, activeLeagues) { current, active ->
            SportsLeagues.filter { it.id in active }.map { league ->
                val board = (current[league.id] as? BoardState.Content)?.board
                LeagueUi(
                    league = league,
                    liveCount = board?.fixtures?.count { it.state == FixtureState.LIVE } ?: 0,
                    loading = current[league.id] is BoardState.Loading,
                )
            }
        }.stateIn(
            viewModelScope,
            SharingStarted.WhileSubscribed(5_000),
            SportsLeagues.filter { it.defaultActive }.map { LeagueUi(it, 0, false) },
        )

    /** Fixtures involving followed teams or athletes — the "My teams" chips. */
    val myTeamFixtures: StateFlow<List<Fixture>> =
        combine(boards, followedTeams, followedPlayers) { current, teams, players ->
            if (teams.isEmpty() && players.isEmpty()) return@combine emptyList()
            current.values.filterIsInstance<BoardState.Content>()
                .flatMap { it.board.fixtures }
                .filter { involvesFollowedTeam(it, teams) || involvesFollowedPlayer(it, players) }
                .sortedWith(compareBy<Fixture> { it.state != FixtureState.LIVE }.thenBy { it.dateMs })
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** One sport coin on the rail: the followed leagues of one ESPN sport, aggregated. */
    data class SportUi(
        val espnSport: String,
        val followedLeagues: Int,
        val liveCount: Int,
        val loading: Boolean,
    )

    /** Hub coin order (soccer first, …); anything unlisted falls to the end, alphabetical. */
    private val sportOrder = listOf(
        "soccer", "tennis", "basketball", "golf", "football", "baseball", "hockey", "racing",
        "mma", "cricket", "volleyball", "rugby", "rugby-league", "australian-football",
    )

    /** The rail's sport coins: every ESPN sport that has at least one followed league. */
    val sportCoins: StateFlow<List<SportUi>> =
        combine(boards, activeLeagues) { current, active ->
            SportsLeagues.filter { it.id in active }
                .groupBy { it.espnSport }
                .map { (sport, leagues) ->
                    SportUi(
                        espnSport = sport,
                        followedLeagues = leagues.size,
                        liveCount = leagues.sumOf { league ->
                            (current[league.id] as? BoardState.Content)?.board?.fixtures
                                ?.count { it.state == FixtureState.LIVE } ?: 0
                        },
                        loading = leagues.any { current[it.id] is BoardState.Loading },
                    )
                }
                .sortedWith(
                    compareBy<SportUi> { sportOrder.indexOf(it.espnSport) }
                        .thenBy { it.espnSport },
                )
        }.stateIn(
            viewModelScope,
            SharingStarted.WhileSubscribed(5_000),
            emptyList(),
        )

    /**
     * Everything the user picked: every fixture of an active league plus games of followed teams or
     * athletes (even when that item's league is not active), live games first — narrowed to the
     * selected sport coin unless it is [SportAllId].
     */
    val favourites: StateFlow<List<Fixture>> =
        combine(boards, activeLeagues, followedTeams, followedPlayers, _selectedSportId) { current, active, teams, players, sport ->
            val sportOf = SportsLeagues.associateBy({ it.id }, { it.espnSport })
            current.values.filterIsInstance<BoardState.Content>()
                .flatMap { it.board.fixtures }
                .filter {
                    val inSport = sport == SportAllId || sportOf[it.leagueId] == sport
                    (it.leagueId in active && inSport) ||
                        (inSport && (involvesFollowedTeam(it, teams) || involvesFollowedPlayer(it, players)))
                }
                .sortedWith(compareBy<Fixture> { it.state != FixtureState.LIVE }.thenBy { it.dateMs })
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** The fixtures currently shown: the selected league's, or the Favourites aggregate. */
    val currentFixtures: StateFlow<List<Fixture>> =
        combine(_selectedLeagueId, selectedBoard, favourites) { id, board, favs ->
            if (id == FavouritesId) favs else (board as? BoardState.Content)?.board?.fixtures.orEmpty()
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** True while the current view still waits for boards (league or Favourites). */
    val currentLoading: StateFlow<Boolean> =
        combine(_selectedLeagueId, selectedBoard, loadingLeagues, boards) { id, board, want, b ->
            if (id == FavouritesId) want.any { b[it] == null || b[it] is BoardState.Loading }
            else board == null || board is BoardState.Loading
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), true)

    /** True when the selected league's board failed; the Favourites aggregate never errors as a whole. */
    val currentError: StateFlow<Boolean> =
        combine(_selectedLeagueId, selectedBoard) { id, board -> id != FavouritesId && board is BoardState.Error }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    init {
        // The TV guide is fetched once in the background: fixture selection must never wait on it.
        viewModelScope.launch(Dispatchers.IO) { guideApi.refresh() }
        // When that scrape lands, re-match the open fixture: the guide's own broadcaster list is
        // the top of the list, and the user should not have to re-select to see it.
        viewModelScope.launch { guideApi.version.drop(1).collect { reResolveSelected() } }
        // A new selection resets the tab to its default (Live if the view has live games, else
        // Upcoming). The boards arrive asynchronously, so re-apply until the user picks by hand.
        viewModelScope.launch {
            _selectedLeagueId.collect {
                userChoseTab = false
                applyDefaultTab()
            }
        }
        viewModelScope.launch { currentFixtures.collect { applyDefaultTab() } }
        // Active leagues (and leagues of followed teams) load reactively; without this the hub
        // would only ever show its empty state until a manual refresh.
        viewModelScope.launch { loadingLeagues.collect { applyLeagues(it) } }
        // The matcher reads the live playlists fresh on every match call, but the matches are
        // cached per fixture — so a source switcher change (defaultSourceId, top-right) or any
        // source-list change would otherwise leave stale matches standing. Observe the same
        // scoped id list the provider computes and re-resolve the selected fixture on change.
        // No boards reload (fixtures are source-independent), no polling, no preview touch.
        viewModelScope.launch {
            settings.activeProfileId.flatMapLatest { pid ->
                if (pid < 0) flowOf(emptyList())
                else combine(sourceRepository.observeSources(pid), settings.defaultSourceId) { sources, defaultId ->
                    scopeLiveSourceIds(sources, defaultId)
                }
            }.distinctUntilChanged().drop(1).collect {
                reResolveSelected()
                clearAiCache()
            }
        }
        // DeepSeek matching is lazy: one search + one confirmation for the fixture the user opened
        // on the DeepSeek tab. The board itself is never sent through the paid phase-2 path, and the
        // matcher caches discovery, search and confirmation for that fixture.
        viewModelScope.launch {
            val flags = combine(
                combine(aiEnabled, aiSportsMatching, deepSeekApiKey) { enabled, matching, key ->
                    Triple(enabled && matching, key, Unit)
                },
                combine(aiCountries, preferredCountry, guideApi.version) { countries, market, version ->
                    Triple(countries.map { it.code }.toSet(), market.takeIf { it.isNotBlank() }, version)
                },
                aiBoardGeneration,
            ) { a, b, generation ->
                BoardAiInput(a.first, a.second, b.first, b.second, b.third, generation)
            }
            combine(_selectedFixture, _classicAiSelected, flags) { fixture, deepSeek, input ->
                Triple(fixture, deepSeek, input)
            }
                .debounce(BoardAiDebounceMs)
                .distinctUntilChanged()
                .collectLatest { (fixture, deepSeek, input) ->
                    if (fixture != null && deepSeek) runFixtureAi(fixture, input)
                }
        }
    }

    /** A source change invalidates cached channel ids; the next AI search resolves them again. */
    private fun clearAiCache() {
        clearAiRecommendation()
        aiByFixture.clear()
        aiByRequest.clear()
        playlistAiRequest++
        playlistAiJob?.cancel()
        aiPlaylistByFixture.clear()
        _playlistAiState.value = PlaylistAiState.Idle
        aiBoardToken++
        aiBoardByFixture.clear()
        _aiBoardHits.value = emptyMap()
        aiBoardGeneration.value++
    }

    fun setTab(tab: FixtureTab) {
        userChoseTab = true
        _tab.value = tab
    }

    private fun applyDefaultTab() {
        if (userChoseTab) return
        val live = currentFixtures.value.count { it.state == FixtureState.LIVE }
        _tab.value = if (live > 0) FixtureTab.LIVE else FixtureTab.UPCOMING
    }

    /** True when [fixture] involves one of the user's followed teams. */
    private fun involvesFollowedTeam(fixture: Fixture, teams: Set<String>): Boolean {
        val home = fixture.home?.abbreviation
        val away = fixture.away?.abbreviation
        return (home != null && teams.contains(SportsPrefs.teamKey(fixture.leagueId, home))) ||
            (away != null && teams.contains(SportsPrefs.teamKey(fixture.leagueId, away)))
    }

    /** True when [fixture] features one of the user's followed athletes (a duel entry). */
    private fun involvesFollowedPlayer(fixture: Fixture, players: Set<String>): Boolean =
        players.isNotEmpty() && fixture.entries.any { entry ->
            entry.id != null && players.contains(SportsPrefs.playerKey(fixture.leagueId, entry.id))
        }

    private suspend fun applyLeagues(want: Set<String>) {
        if (boards.value.keys.any { it !in want }) {
            boards.value = boards.value.filterKeys { it in want }
        }
        standingsCache.keys.retainAll(want)
        val selected = _selectedLeagueId.value
        if (selected != FavouritesId && selected !in want) {
            _selectedLeagueId.value = SportsLeagues.firstOrNull { it.id in want }?.id ?: FavouritesId
            _selectedFixture.value = null
            _matches.value = MatchesState.Idle
        }
        supervisorScope {
            SportsLeagues.filter { it.id in want }.map { league ->
                async { loadBoard(league, force = false) }
            }.awaitAll()
        }
    }

    fun refreshSelected() {
        val id = _selectedLeagueId.value
        if (id == FavouritesId) {
            val want = loadingLeagues.value
            viewModelScope.launch {
                supervisorScope {
                    SportsLeagues.filter { it.id in want }.map { async { loadBoard(it, force = true) } }.awaitAll()
                }
            }
            return
        }
        val league = sportsLeagueById(id) ?: return
        viewModelScope.launch { loadBoard(league, force = true) }
    }

    /** Show the Favourites aggregate (active leagues + followed teams) instead of a single league. */
    fun selectFavourites() {
        _selectedLeagueId.value = FavouritesId
        _selectedFixture.value = null
        _matches.value = MatchesState.Idle
        _aiState.value = AiState.Idle
        _playlistAiState.value = PlaylistAiState.Idle
    }

    /** The aggregate, narrowed to one sport coin ([SportAllId] = everything). */
    fun selectSport(espnSport: String) {
        if (_selectedLeagueId.value != FavouritesId) selectFavourites()
        _selectedSportId.value = espnSport
    }

    fun selectLeague(id: String) {
        _selectedLeagueId.value = id
        _selectedFixture.value = null
        _matches.value = MatchesState.Idle
        _aiState.value = AiState.Idle
        _playlistAiState.value = PlaylistAiState.Idle
        val league = sportsLeagueById(id) ?: return
        viewModelScope.launch {
            loadBoard(league, force = false)
            loadStandings(league)
        }
    }

    fun selectFixture(fixture: Fixture?) {
        val previousId = _selectedFixture.value?.id
        _selectedFixture.value = fixture
        matchesRequest++
        if (fixture == null) {
            _matches.value = MatchesState.Idle
            _aiState.value = AiState.Idle
            return
        }
        // Restore this fixture's last answer; a finished request must never need a second paid call.
        // While the AI functions are off, no answer is shown — the list is not on screen anyway.
        if (previousId != fixture.id) {
            _aiState.value = if (aiEnabled.value) aiByFixture[fixture.id] ?: AiState.Idle else AiState.Idle
            _playlistAiState.value = aiPlaylistByFixture[fixture.id]
                ?.let { PlaylistAiState.Content(it.picks.size, it.candidateCount) }
                ?: PlaylistAiState.Idle
        }
        // In Favourites the "selected league" is the aggregate, so follow the fixture's own league
        // for the standings panel.
        if (_selectedLeagueId.value == FavouritesId) {
            sportsLeagueById(fixture.leagueId)?.let { league -> viewModelScope.launch { loadStandings(league) } }
        }
        matchesCache[fixture.id]?.let {
            _matches.value = MatchesState.Content(it)
            return
        }
        _matches.value = MatchesState.Loading
        val request = matchesRequest
        viewModelScope.launch {
            val found = runCatching { matcher.match(fixture, matchOptions()) }.getOrDefault(emptyList())
            if (request != matchesRequest) return@launch
            matchesCache[fixture.id] = found
            if (_selectedFixture.value?.id == fixture.id) _matches.value = MatchesState.Content(found)
        }
    }

    fun toggleLeagueFollowed(leagueId: String) {
        viewModelScope.launch {
            prefs.setLeagueFollowed(leagueId, activeLeagues.value.contains(leagueId).not())
        }
    }

    fun toggleTeamFollowed(leagueId: String, abbrev: String) {
        viewModelScope.launch {
            val key = SportsPrefs.teamKey(leagueId, abbrev)
            prefs.setTeamFollowed(key, followedTeams.value.contains(key).not())
        }
    }

    fun togglePlayerFollowed(leagueId: String, playerId: String) {
        viewModelScope.launch {
            val key = SportsPrefs.playerKey(leagueId, playerId)
            prefs.setPlayerFollowed(key, followedPlayers.value.contains(key).not())
        }
    }

    fun isTeamFollowed(leagueId: String, abbrev: String): Boolean =
        followedTeams.value.contains(SportsPrefs.teamKey(leagueId, abbrev))

    fun isPlayerFollowed(leagueId: String, playerId: String): Boolean =
        followedPlayers.value.contains(SportsPrefs.playerKey(leagueId, playerId))

    /** Resolve matched ids to entities for the zap context, then hand off to the player caller. */
    fun playMatch(match: ChannelMatch, matches: List<ChannelMatch>, onPlay: (Long, List<tv.own.owntv.core.database.entity.ChannelEntity>) -> Unit) {
        viewModelScope.launch {
            val entities = runCatching { matcher.channels(matches.map { it.channelId }) }.getOrDefault(emptyList())
            onPlay(match.channelId, entities)
        }
    }

    /** Entities for the channel matches, in order — for the in-pane preview. */
    suspend fun entitiesFor(matches: List<ChannelMatch>): List<tv.own.owntv.core.database.entity.ChannelEntity> =
        runCatching { matcher.channels(matches.map { it.channelId }) }.getOrDefault(emptyList())

    fun setHubVisible(visible: Boolean) {
        viewModelScope.launch { prefs.setHubVisible(visible) }
    }

    /** Choose the languages matched channels are filtered to; empty = all. Marks the prompt answered. */
    fun setPreferredLanguages(languages: Set<SportLanguage>) {
        viewModelScope.launch {
            prefs.setPreferredLanguages(languages.map { it.name }.toSet())
            // The prefs flow may lag the write, so re-resolve with the just-picked value directly.
            reResolveSelected(matchOptions().copy(languages = languages))
        }
    }

    /** How many channels one fixture lists (5–50); the selected fixture re-resolves at once. */
    fun setChannelLimit(limit: Int) {
        viewModelScope.launch {
            prefs.setChannelLimit(limit)
            reResolveSelected(matchOptions().copy(limit = limit))
        }
    }

    /** Store the DeepSeek key from Sport settings. */
    fun setDeepSeekApiKey(key: String) {
        viewModelScope.launch { prefs.setDeepSeekApiKey(key) }
    }

    /** How many AI suggestions one request lists (5–50). */
    fun setAiSuggestionLimit(limit: Int) {
        viewModelScope.launch { prefs.setAiSuggestionLimit(limit) }
    }

    /** Switch the detail pane's channel list between classic matching and AI suggestions. */
    fun setAiListSelected(selected: Boolean) {
        // While the AI functions are off there is no AI list to show.
        _aiListSelected.value = selected && aiEnabled.value
    }

    /** Switch between the Classic matcher and the lazy DeepSeek Match column. */
    fun setClassicAiSelected(selected: Boolean) {
        val next = selected && aiEnabled.value
        if (_classicAiSelected.value == next) return
        _classicAiSelected.value = next
        viewModelScope.launch { reResolveSelected() }
    }

    /**
     * Run the AI recommendation for [fixture] in [countries]. A completed answer is cached by
     * fixture and by country set, so returning from playback or another fixture never triggers a
     * second paid API call. A newer request wins over a late answer.
     */
    fun recommendWithAi(fixture: Fixture, countries: Set<AiCountry>) {
        // The switch is the last door before a paid request leaves the device.
        if (!aiEnabled.value) {
            _aiState.value = AiState.Error(AiFailure.DISABLED)
            return
        }
        val orderedCountries = AiCountry.entries.filter { it in countries }
        aiRequest++
        val request = aiRequest
        aiJob?.cancel()
        if (orderedCountries.isEmpty()) {
            _aiState.value = AiState.Error(AiFailure.EMPTY)
            return
        }
        _aiListSelected.value = true
        val requestKey = aiRequestKey(fixture, orderedCountries)
        aiByRequest[requestKey]?.let { cached ->
            aiByFixture[fixture.id] = cached
            if (_selectedFixture.value?.id == fixture.id) _aiState.value = cached
            viewModelScope.launch {
                runCatching { prefs.setAiCountries(orderedCountries.map { it.code }.toSet()) }
            }
            return
        }
        val key = deepSeekApiKey.value.trim()
        if (key.isEmpty()) {
            _aiState.value = AiState.Error(AiFailure.NO_KEY)
            return
        }
        _aiState.value = AiState.Loading
        aiJob = viewModelScope.launch {
            runCatching { prefs.setAiCountries(orderedCountries.map { it.code }.toSet()) }
            val broadcasts = try {
                aiClient.recommend(fixture, orderedCountries, key)
            } catch (e: AiRequestException) {
                if (request == aiRequest && _selectedFixture.value?.id == fixture.id) {
                    _aiState.value = AiState.Error(e.failure)
                }
                return@launch
            } catch (e: Exception) {
                if (request == aiRequest && _selectedFixture.value?.id == fixture.id) {
                    _aiState.value = AiState.Error(AiFailure.UNKNOWN)
                }
                return@launch
            }
            val suggestions = runCatching {
                aiMatcher.match(broadcasts, aiSuggestionLimit.value)
            }.getOrDefault(emptyList())
                // "Always hide" stays a hard never, even for an explicitly selected country.
                .filterNot { SportLanguages.languageOfName(it.channelName) in excludedLanguages.value }
            val content = AiState.Content(suggestions, orderedCountries.toSet())
            aiByRequest[requestKey] = content
            aiByFixture[fixture.id] = content
            if (request == aiRequest && _selectedFixture.value?.id == fixture.id) {
                _aiState.value = content
            }
        }
    }

    /** Cancel the running AI request and drop only the current view state; completed answers stay cached. */
    fun clearAiRecommendation() {
        aiRequest++
        aiJob?.cancel()
        aiJob = null
        _aiState.value = AiState.Idle
    }

    /**
     * Run the full-playlist model analysis for [fixture] — explicitly, after the user agreed to
     * send the event, guide/EPG text and playlist channel names. Never called on render.
     *
     * The accepted answer is merged as one more independent evidence source; a valid `none` or an
     * API failure leaves guide/EPG results untouched.
     */
    fun analyzePlaylistWithAi(fixture: Fixture) {
        if (!aiEnabled.value) {
            _playlistAiState.value = PlaylistAiState.Error(AiFailure.DISABLED)
            return
        }
        val key = deepSeekApiKey.value.trim()
        if (key.isEmpty()) {
            _playlistAiState.value = PlaylistAiState.Error(AiFailure.NO_KEY)
            return
        }
        playlistAiRequest++
        val request = playlistAiRequest
        playlistAiJob?.cancel()
        _playlistAiState.value = PlaylistAiState.Loading
        playlistAiJob = viewModelScope.launch {
            val result = try {
                aiPlaylistAnalyst.analyze(
                    fixture = fixture,
                    apiKey = key,
                    market = preferredCountry.value.takeIf { it.isNotBlank() },
                    guideVersion = guideApi.version.value,
                    guideEvidence = guideApi.evidenceFor(fixture),
                )
            } catch (e: AiRequestException) {
                if (request == playlistAiRequest) {
                    _playlistAiState.value = PlaylistAiState.Error(e.failure)
                }
                return@launch
            } catch (e: Exception) {
                if (request == playlistAiRequest) {
                    _playlistAiState.value = PlaylistAiState.Error(AiFailure.UNKNOWN)
                }
                return@launch
            }
            if (request != playlistAiRequest) return@launch
            aiPlaylistByFixture[fixture.id] = result
            // Diagnostics only: counts, timing and validity — never names, ids or stream data.
            android.util.Log.d(
                "SportsHub",
                "playlist ai candidates=" + result.candidateCount +
                    " requests=" + result.requestCount +
                    " valid=" + result.validResponses +
                    " invalid=" + result.invalidResponses +
                    " inputChars=" + result.inputChars +
                    " ms=" + result.latencyMs,
            )
            _playlistAiState.value = if (result.validResponses == 0 && result.invalidResponses > 0) {
                PlaylistAiState.Error(AiFailure.UNKNOWN)
            } else {
                PlaylistAiState.Content(result.picks.size, result.candidateCount)
            }
            if (_selectedFixture.value?.id == fixture.id) reResolveSelected()
        }
    }

    /** Entities for the AI suggestions, in order — for the inline preview. */
    suspend fun entitiesForAi(suggestions: List<AiSuggestion>): List<tv.own.owntv.core.database.entity.ChannelEntity> =
        runCatching { matcher.channels(suggestions.map { it.channelId }) }.getOrDefault(emptyList())

    /** Stable cache key: one fixture in one country set. */
    private fun aiRequestKey(fixture: Fixture, countries: List<AiCountry>): String =
        fixture.id + AiKeySeparator + countries.joinToString(AiKeyCountrySeparator) { it.code }

    /** Resolve an AI suggestion to entities for the zap context, like [playMatch]. */
    fun playAiSuggestion(
        suggestion: AiSuggestion,
        suggestions: List<AiSuggestion>,
        onPlay: (Long, List<tv.own.owntv.core.database.entity.ChannelEntity>) -> Unit,
    ) {
        viewModelScope.launch {
            val entities = runCatching { matcher.channels(suggestions.map { it.channelId }) }
                .getOrDefault(emptyList())
            onPlay(suggestion.channelId, entities)
        }
    }

    /** Toggle one excluded language; the selected fixture re-resolves at once. */
    fun toggleExcludedLanguage(language: SportLanguage) {
        viewModelScope.launch {
            val current = excludedLanguages.value
            val next = if (language in current) current - language else current + language
            prefs.setExcludedLanguages(next.map { it.name }.toSet())
            reResolveSelected(matchOptions().copy(excludedLanguages = next))
        }
    }

    /** Set the preferred channel country ("" = no preference); the selected fixture re-resolves. */
    fun setPreferredCountry(code: String) {
        viewModelScope.launch {
            prefs.setPreferredCountry(code)
            val next = code.takeIf { it in SportLanguages.preferredCountryOptions }.orEmpty()
            reResolveSelected(matchOptions().copy(preferCountry = next.takeIf { it.isNotBlank() }))
        }
    }

    /**
     * The matcher's filter from the current prefs plus the independent evidence for the selected
     * fixture: guide carriers, ranked priors (tie-break only) and any accepted full-playlist AI
     * rows. All reads are cached — never a network call.
     */
    private fun matchOptions(withAiEvidence: Boolean = _classicAiSelected.value): SportMatchOptions {
        val fixture = _selectedFixture.value
        val market = preferredCountry.value.takeIf { it.isNotBlank() }
        val includeAi = withAiEvidence && aiEnabled.value
        return SportMatchOptions(
            languages = preferredLanguages.value,
            excludedLanguages = excludedLanguages.value,
            limit = channelLimit.value,
            strict = languageFilterStrict.value,
            preferCountry = market,
            // Classic keeps the original guide/EPG evidence. DeepSeek is only the accepted,
            // evidence-tiered AI rows and never merges Classic output into its column.
            confirmedBroadcasters = if (includeAi) {
                emptyList()
            } else {
                fixture?.let { guideApi.confirmedBroadcasters(it) }.orEmpty()
            },
            priorCarriers = if (includeAi) {
                emptyList()
            } else {
                fixture?.let { priors.carriersFor(it, market) }.orEmpty()
            },
            aiChannels = if (includeAi) {
                fixture?.let { aiBoardByFixture[it.id].orEmpty() }.orEmpty()
            } else {
                emptyList()
            },
            aiOnly = includeAi,
            // Classic keeps its UK/US/FR/4K bands; DeepSeek sorts by evidence tier first.
            classicPrefixOrder = !includeAi,
        )
    }

    /** Drop the cached channels and match the selected fixture again (per-game refresh button). */
    fun refreshMatches() {
        viewModelScope.launch { reResolveSelected() }
    }

    /**
     * Matches are language-filtered inside the matcher, so the cache is only valid for the options
     * it was built with — drop it and re-resolve the selected fixture.
     */
    private suspend fun reResolveSelected(options: SportMatchOptions? = null) {
        val request = ++matchesRequest
        matchesCache.clear()
        val fixture = _selectedFixture.value ?: return
        _matches.value = MatchesState.Loading
        val resolved = options ?: matchOptions()
        val found = runCatching { matcher.match(fixture, resolved) }.getOrDefault(emptyList())
        if (request != matchesRequest) return
        matchesCache[fixture.id] = found
        if (_selectedFixture.value?.id == fixture.id) _matches.value = MatchesState.Content(found)
    }

    /** Remember that the language prompt was shown, so it does not reappear on the next fixture. */
    fun markLanguageAsked() {
        viewModelScope.launch { prefs.setLanguageAsked() }
    }

    // ---- Phone management (companion pick list) -------------------------------------------------

    /** The companion server's state while the "Manage on phone" panel is open (Idle otherwise). */
    val remoteState: StateFlow<CompanionServerState> = companion.state

    /**
     * Serve the pick list (leagues + every active league's teams and athletes) to a phone on the same
     * Wi-Fi. Teams come from ESPN's `/teams`; athletes from the already-loaded duel fixtures. Chosen
     * ids reconcile the active leagues, followed teams and followed athletes in one submit.
     *
     * Teams and athletes are children of their league ([CompanionSelectItem.parentId]), so the phone
     * page opens one level per league instead of one endless list; [teamGroup] and [playerGroup]
     * label the two tabs inside it.
     */
    fun startPhonePicker(
        port: Int,
        title: String,
        leagueItems: List<CompanionSelectItem>,
        leagueLabels: Map<String, String>,
        teamGroup: String,
        playerGroup: String,
    ) {
        viewModelScope.launch {
            val shownLeagues = activeLeagues.value
            val extra = ArrayList<CompanionSelectItem>()
            shownLeagues.mapNotNull { sportsLeagueById(it) }
                .sortedBy { leagueLabels[it.id] ?: it.id }
                .forEach { league ->
                    runCatching { api.teams(league) }.getOrDefault(emptyList()).forEach { team ->
                        val key = SportsPrefs.teamKey(league.id, team.abbreviation)
                        extra.add(
                            CompanionSelectItem(
                                TeamItemPrefix + key, team.name, teamGroup,
                                key in followedTeams.value, parentId = league.id,
                            ),
                        )
                    }
                    val seen = HashSet<String>()
                    boards.value[league.id].let { it as? BoardState.Content }?.board?.fixtures.orEmpty()
                        .flatMap { it.entries }
                        .filter { it.id != null && !it.id.startsWith("-") }
                        .sortedBy { it.name.lowercase() }
                        .forEach { entry ->
                            val id = entry.id ?: return@forEach
                            val key = SportsPrefs.playerKey(league.id, id)
                            if (seen.add(key)) {
                                extra.add(
                                    CompanionSelectItem(
                                        PlayerItemPrefix + key, entry.name, playerGroup,
                                        key in followedPlayers.value, parentId = league.id,
                                    ),
                                )
                            }
                        }
                }
            companion.startForSelectItems(port, title, leagueItems + extra) { ids ->
                viewModelScope.launch { applyPhoneSelection(ids, shownLeagues) }
            }
        }
    }

    /**
     * Apply a phone submit: the league ids become the active set; the team/athlete ids replace the
     * followed ones **for the leagues whose lists were shown** (follows in other leagues are kept).
     */
    private suspend fun applyPhoneSelection(ids: Set<String>, shownLeagues: Set<String>) {
        val leagueIds = ids.filterNot { it.startsWith(TeamItemPrefix) || it.startsWith(PlayerItemPrefix) }.toSet()
        val teamKeys = ids.filter { it.startsWith(TeamItemPrefix) }.map { it.removePrefix(TeamItemPrefix) }.toSet()
        val playerKeys = ids.filter { it.startsWith(PlayerItemPrefix) }.map { it.removePrefix(PlayerItemPrefix) }.toSet()
        val shownPrefixes = shownLeagues.map { "$it$TeamKeySep" }
        fun shownInLeagues(key: String) = shownPrefixes.any { key.startsWith(it) }
        prefs.setActiveLeagues(leagueIds)
        prefs.setFollowedTeams((followedTeams.value.filterNot { shownInLeagues(it) } + teamKeys).toSet())
        prefs.setFollowedPlayers((followedPlayers.value.filterNot { shownInLeagues(it) } + playerKeys).toSet())
        delay(1_500)
        companion.stop()
    }

    fun stopPhonePicker() = companion.stop()

    // ---- DeepSeek key from a phone (companion AI-key page) ---------------------------------------

    /** AI provider keys pasted on a phone while the key dialog's "on phone" panel is open. */
    val remoteApiKeys: SharedFlow<String> = companion.apiKeys

    /** Serve the one-field API-key page to a phone on the same Wi-Fi ([CompanionMode.AI_KEY]). */
    fun startRemoteApiKeyListener(port: Int) = companion.startForApiKey(port)

    fun stopRemoteListener() = companion.stop()

    /**
     * One opened fixture on the DeepSeek tab. The key/switch gate is the last door before a paid
     * call; search, extractor and confirmation are cached inside the matcher.
     */
    private suspend fun runFixtureAi(fixture: Fixture, input: BoardAiInput) {
        val token = ++aiBoardToken
        if (!input.enabled || input.apiKey.isBlank()) {
            val had = aiBoardByFixture.remove(fixture.id) != null
            if (had) {
                val next = _aiBoardHits.value.toMutableMap()
                next.remove(fixture.id)
                _aiBoardHits.value = next
                if (_selectedFixture.value?.id == fixture.id) reResolveSelected()
            }
            return
        }
        if (_selectedFixture.value?.id == fixture.id && _classicAiSelected.value) {
            _matches.value = MatchesState.Loading
        }
        val result = try {
            aiBoardMatcher.match(
                fixtures = listOf(fixture),
                countries = (input.countries + listOfNotNull(input.market)).distinct(),
                market = input.market,
                apiKey = input.apiKey,
                guide = { requested ->
                    BoardGuideEvidence(
                        carriers = guideApi.confirmedBroadcasters(requested),
                        rows = guideApi.evidenceFor(requested),
                    )
                },
            )
        } catch (e: kotlin.coroutines.cancellation.CancellationException) {
            throw e
        } catch (e: Exception) {
            null
        } ?: run {
            if (_selectedFixture.value?.id == fixture.id) reResolveSelected()
            return
        }
        if (token != aiBoardToken) return
        // A transient failure is not proof the previous accepted rows vanished; keep them until a
        // valid run replaces them. A valid `none` (validResponses > 0) still clears this fixture.
        val keepPrevious = result.validResponses == 0 &&
            result.failure != null &&
            result.hits.isEmpty() &&
            aiBoardByFixture[fixture.id].orEmpty().isNotEmpty()
        if (!keepPrevious) {
            aiBoardByFixture.remove(fixture.id)
            aiBoardByFixture.putAll(result.hits)
            val next = _aiBoardHits.value.toMutableMap()
            next.remove(fixture.id)
            result.hits.forEach { (id, matches) -> next[id] = matches.size }
            _aiBoardHits.value = next
        }
        // Diagnostics only: counts, timing and validity — never channel names or ids.
        runCatching {
            android.util.Log.d(
                "SportsHub",
                "fixture ai id=" + fixture.id +
                    " suggestions=" + result.discoverySuggestions +
                    " candidates=" + result.candidateCount +
                    " searchHits=" + result.searchHits +
                    " searchCandidates=" + result.searchCandidates +
                    " requests=" + result.adjudicationRequests +
                    " valid=" + result.validResponses +
                    " invalid=" + result.invalidResponses +
                    " inputChars=" + result.inputChars +
                    " ms=" + result.latencyMs +
                    " failure=" + result.failure,
            )
        }
        if (_selectedFixture.value?.id == fixture.id) reResolveSelected()
    }

    private suspend fun loadBoard(league: SportLeague, force: Boolean) {
        // Keep the TV guide fresh in the background — never as part of a selection.
        if (guideApi.isStale()) {
            viewModelScope.launch(Dispatchers.IO) { guideApi.refresh() }
        }
        val now = System.currentTimeMillis()
        if (!force) {
            val at = boardAt[league.id] ?: 0L
            if (now - at < BoardTtlMs && boards.value[league.id] is BoardState.Content) return
            if (boards.value[league.id] is BoardState.Loading) return
        }
        boards.value = boards.value + (league.id to BoardState.Loading)
        // ESPN's racing scoreboard only carries the most recent event (already `post` during the
        // weekend), so F1 boards come from Jolpica's session calendar instead.
        val result = runCatching {
            if (league.id == F1Schedule.F1LeagueId) f1Api.scoreboard() else api.scoreboard(league)
        }
        result.onFailure { android.util.Log.w("SportsHub", "board failed " + league.id, it) }
        result.onSuccess { android.util.Log.d("SportsHub", "board ok " + league.id + " fixtures=" + it.fixtures.size) }
        boardAt[league.id] = now
        boards.value = boards.value + (
            league.id to
                (result.getOrNull()?.let { board ->
                    // Tennis boards carry 250+ matches: bound the data (and the hub's work) — a
                    // fixture list never needs more than the next days' worth per league.
                    val fixtures = if (board.fixtures.size > MaxFixturesPerLeague) {
                        board.fixtures.take(MaxFixturesPerLeague)
                    } else {
                        board.fixtures
                    }
                    BoardState.Content(EspnApi.Scoreboard(board.leagueName, fixtures))
                } ?: BoardState.Error)
            )
    }

    private suspend fun loadStandings(league: SportLeague) {
        standingsCache[league.id]?.let {
            _standings.value = it
            return
        }
        _standingsLoading.value = true
        val tables = runCatching { api.standings(league) }.getOrDefault(emptyList())
        standingsCache[league.id] = tables
        _standings.value = tables
        _standingsLoading.value = false
    }

    companion object {
        /** A league board is capped here — tennis alone returns 280+ matches per tour. */
        private const val MaxFixturesPerLeague = 120
        /** Sentinel rail selection for the Favourites aggregate (no single league). */
        const val FavouritesId = "__favourites__"
        const val SportAllId = "__sport_all__"

        /** Pick-list id prefixes, so one phone submit can carry leagues, teams and athletes. */
        const val TeamItemPrefix = "team:"
        const val PlayerItemPrefix = "player:"

        /** Separator inside [SportsPrefs.teamKey] ("<leagueId>|<abbrev>"). */
        private const val TeamKeySep = '|'

        /** DeepSeek matching re-run debounce. */
        private const val BoardAiDebounceMs = 1_000L

        /** AI cache key separators (fixture id + country codes); technical, never displayed. */
        private const val AiKeySeparator = "|"
        private const val AiKeyCountrySeparator = ","

        private const val BoardTtlMs = 15L * 60L * 1000L
    }
}
