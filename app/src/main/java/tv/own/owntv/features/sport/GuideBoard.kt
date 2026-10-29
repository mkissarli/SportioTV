package tv.own.owntv.features.sport

/**
 * Which leagues use a Where's The Match per-sport page, and which pages those are.
 *
 * The guide's rows are the *carrier* evidence for these sports (the fixtures come from FightNights
 * and the Wikipedia calendars); they are also the fallback board while a calendar source has not
 * answered yet. The mapping is derived from the catalogue (`espnLeague = "wtm"`), so a new
 * guide-backed league needs only its [SportLeague] entry.
 */
object GuideBoard {

    /** League id → guide sport page slug (the league's own prefix: `boxing.world` → `boxing`). */
    val sports: Map<String, String> =
        SportsLeagues.filter { it.espnLeague == WtmLeagueSlug }
            .associate { it.id to it.id.substringBefore('.') }

    /** The distinct sport pages one guide refresh has to fetch. */
    val sportPages: List<String> = sports.values.distinct()

    /** The catalogue marks guide-backed leagues with this pseudo ESPN slug. */
    const val WtmLeagueSlug = "wtm"
}
