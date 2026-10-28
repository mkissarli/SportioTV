package tv.own.owntv.features.sport

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.annotation.StringRes
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.tv.material3.Text
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import org.koin.androidx.compose.koinViewModel
import org.koin.compose.koinInject
import tv.own.owntv.R
import tv.own.owntv.core.companion.CompanionLink
import tv.own.owntv.core.companion.CompanionSelectItem
import tv.own.owntv.core.companion.CompanionServerState
import tv.own.owntv.core.database.entity.ChannelEntity
import tv.own.owntv.features.epg.GuideVideo
import tv.own.owntv.features.live.LiveViewModel
import tv.own.owntv.ui.components.OwnTVIcon
import tv.own.owntv.ui.components.OwnTVPopup
import tv.own.owntv.ui.components.OwnTVTextField
import tv.own.owntv.ui.components.StageCompanionStatus
import tv.own.owntv.ui.components.trapAllFocusExit
import tv.own.owntv.ui.stage.StageButton
import tv.own.owntv.ui.stage.StageConfirm
import tv.own.owntv.ui.stage.StageFocus
import tv.own.owntv.ui.stage.StageGroupLabel
import tv.own.owntv.ui.stage.StageMenu
import tv.own.owntv.ui.stage.StageMenuHeader
import tv.own.owntv.ui.stage.StageMenuItem
import tv.own.owntv.ui.stage.StagePopup
import tv.own.owntv.ui.stage.StagePopupIcon
import tv.own.owntv.ui.stage.StagePopupOption
import tv.own.owntv.ui.stage.StageSurface
import tv.own.owntv.ui.stage.stageGlass
import tv.own.owntv.ui.stage.StageTab
import tv.own.owntv.core.theme.GlassSurface
import tv.own.owntv.core.theme.SportioPalette
import tv.own.owntv.core.theme.teamColor
import tv.own.owntv.ui.theme.LocalStagePalette
import tv.own.owntv.ui.theme.StageColors
import tv.own.owntv.ui.theme.mpx
import tv.own.owntv.ui.theme.stageAccent
import tv.own.owntv.ui.theme.stageText

/**
 * Sports hub (Stage): league rail, a Live / Upcoming / Past tabbed fixture list, and a detail pane
 * with venue, broadcasts, standings and the matched user channels — one tap
 * on a channel starts playback through the shared LiveViewModel zap path.
 */
@Composable
fun SportsScreen(
    onPlayChannel: (Long, List<ChannelEntity>, String?) -> Unit,
    onChildFocused: () -> Unit,
    previewEnabled: Boolean = true,
    restoreFocus: Boolean = false,
    onRestored: () -> Unit = {},
    onEntryHook: (((() -> Boolean)?) -> Unit)? = null,
    onContentScrolled: (Boolean) -> Unit = {},
    /** Opens the app's own Settings (playlists, profile, EPG, updates) from the hub's settings dialog. */
    onOpenAppSettings: () -> Unit = {},
    /** Focus target for the top-right cluster's Down key, so the pills always have a way back in. */
    entryFocusRequester: FocusRequester? = null,
    /** Opened by Settings › Sport: show the sport settings dialog as soon as the hub is on screen. */
    openSettingsOnStart: Boolean = false,
    onSettingsOpened: () -> Unit = {},
    contentStart: Dp = 150.mpx,
    modifier: Modifier = Modifier,
) {
    val vm: SportsViewModel = koinViewModel()
    val liveVm: LiveViewModel = koinViewModel()
    val sportCoins by vm.sportCoins.collectAsStateWithLifecycle()
    val selectedSportId by vm.selectedSportId.collectAsStateWithLifecycle()
    val activeLeagues by vm.activeLeagues.collectAsStateWithLifecycle()
    val selectedLeagueId by vm.selectedLeagueId.collectAsStateWithLifecycle()
    val selectedFixture by vm.selectedFixture.collectAsStateWithLifecycle()
    val matchesState by vm.matches.collectAsStateWithLifecycle()
    val standings by vm.standings.collectAsStateWithLifecycle()
    val standingsLoading by vm.standingsLoading.collectAsStateWithLifecycle()
    val liveMatches by vm.liveMatches.collectAsStateWithLifecycle()
    val followedTeams by vm.followedTeams.collectAsStateWithLifecycle()
    val followedPlayers by vm.followedPlayers.collectAsStateWithLifecycle()
    val livePreviewSetting by liveVm.livePreviewEnabled.collectAsStateWithLifecycle()
    val previewArmed by liveVm.previewArmed.collectAsStateWithLifecycle()
    val effectivePreview = previewEnabled && livePreviewSetting
    val preferredLanguages by vm.preferredLanguages.collectAsStateWithLifecycle()
    val preferredCountry by vm.preferredCountry.collectAsStateWithLifecycle()
    val preferredPlaylistId by vm.preferredPlaylistId.collectAsStateWithLifecycle()
    val playlists by vm.playlists.collectAsStateWithLifecycle()
    val channelLimit by vm.channelLimit.collectAsStateWithLifecycle()
    val excludedLanguages by vm.excludedLanguages.collectAsStateWithLifecycle()
    val deepSeekApiKey by vm.deepSeekApiKey.collectAsStateWithLifecycle()
    val aiSuggestionLimit by vm.aiSuggestionLimit.collectAsStateWithLifecycle()
    val aiCountries by vm.aiCountries.collectAsStateWithLifecycle()
    val aiState by vm.aiState.collectAsStateWithLifecycle()
    val playlistAiState by vm.playlistAiState.collectAsStateWithLifecycle()
    val aiListSelected by vm.aiListSelected.collectAsStateWithLifecycle()
    val classicAiSelected by vm.classicAiSelected.collectAsStateWithLifecycle()
    val aiEnabled by vm.aiEnabled.collectAsStateWithLifecycle()
    var showSettings by remember { mutableStateOf(false) }
    var settingsTab by remember { mutableIntStateOf(0) }
    var showLanguage by remember { mutableStateOf(false) }
    var showPhone by remember { mutableStateOf(false) }
    var showAiCountries by remember { mutableStateOf(false) }
    var showAiKeyMissing by remember { mutableStateOf(false) }
    var showPlaylistAiConsent by remember { mutableStateOf(false) }
    val remoteState by vm.remoteState.collectAsStateWithLifecycle()

    // The AI functions were switched off while an AI column was open: the original classic list
    // takes over, and the AI-enriched classic column disappears with them.
    LaunchedEffect(aiEnabled) {
        if (!aiEnabled && aiListSelected) vm.setAiListSelected(false)
        if (!aiEnabled && classicAiSelected) vm.setClassicAiSelected(false)
    }

    val favouritesSelected = selectedLeagueId == SportsViewModel.FavouritesId
    val fixtures by vm.currentFixtures.collectAsStateWithLifecycle()
    val boardError by vm.currentError.collectAsStateWithLifecycle()
    val boardLoading by vm.currentLoading.collectAsStateWithLifecycle()

    // Live / Demnächst / Vergangen filter for the fixtures list.
    val tab by vm.tab.collectAsStateWithLifecycle()
    val visibleFixtures = remember(fixtures, tab) { fixturesFor(fixtures, tab) }

    // Matchday-style hub rows: the list becomes date bands → league bands → games (foldable).
    val foldedLeagues = remember { mutableStateMapOf<String, Boolean>() }
    LaunchedEffect(selectedSportId) { foldedLeagues.clear() }
    val hubRows = remember(visibleFixtures, favouritesSelected, foldedLeagues.toMap()) {
        buildHubRows(visibleFixtures, favouritesSelected, foldedLeagues, ZoneId.systemDefault(), LocalDate.now(ZoneId.systemDefault()))
    }

    // Phone picker payload: labels/regions resolved here, where stringResource is available.
    val phoneTitle = stringResource(R.string.sport_phone_title)
    val phoneTeamGroup = stringResource(R.string.sport_phone_teams)
    val phonePlayerGroup = stringResource(R.string.sport_phone_players)
    val phoneLeagueItems = SportsLeagues.map { league ->
        CompanionSelectItem(
            id = league.id,
            label = stringResource(league.labelRes),
            group = stringResource(league.region.labelRes),
            selected = league.id in activeLeagues,
        )
    }
    val phoneLeagueLabels = SportsLeagues.associate { it.id to stringResource(it.labelRes) }

    val sportFocus = remember { mutableStateMapOf<String, FocusRequester>() }
    fun sportRequester(id: String) = sportFocus.getOrPut(id) { FocusRequester() }
    val gameFocus = remember { mutableStateMapOf<String, FocusRequester>() }
    fun gameRequester(id: String) = gameFocus.getOrPut(id) { FocusRequester() }
    val matchFocus = remember { mutableStateMapOf<Long, FocusRequester>() }
    fun matchRequester(id: Long) = matchFocus.getOrPut(id) { FocusRequester() }
    val refreshFocus = remember { FocusRequester() }
    val settingsFocus = remember { FocusRequester() }
    val listState = rememberLazyListState()

    suspend fun focusGame(id: String) {
        if (runCatching { gameRequester(id).requestFocus() }.isSuccess) return
        val index = hubRows.indexOfFirst { it is HubItem.Game && it.fixture.id == id }
        if (index >= 0) runCatching { listState.scrollToItem(index) }
        withFrameNanos { }
        // Coming back from Settings the board may still be loading, so the row can genuinely not exist
        // yet: land on the rail instead of leaving focus wherever it was, or nowhere at all.
        if (runCatching { gameRequester(id).requestFocus() }.isSuccess) return
        runCatching { sportRequester(SportsViewModel.SportAllId).requestFocus() }
    }

    val entry: () -> Boolean = {
        val firstFixture = visibleFixtures.firstOrNull() ?: fixtures.firstOrNull()
        if (firstFixture != null) runCatching { gameRequester(firstFixture.id).requestFocus() }.isSuccess
        else runCatching { sportRequester(SportsViewModel.SportAllId).requestFocus() }.isSuccess
    }
    DisposableEffect(onEntryHook, fixtures) {
        onEntryHook?.invoke(entry)
        onDispose { onEntryHook?.invoke(null) }
    }

    // Detail always populated: pick the live game first, else the next upcoming one. A selected
    // fixture that dropped out of the current sport filter (e.g. after switching coins) is treated
    // as no selection so the pane re-picks from the visible games.
    LaunchedEffect(fixtures) {
        val current = selectedFixture
        // The collected state lags behind selectFixture's write, so never null-and-repick;
        // decide from the stale check alone.
        if (current == null || fixtures.none { it.id == current.id }) {
            val pick = fixtures.firstOrNull { it.state == FixtureState.LIVE }
                ?: fixtures.filter { it.state == FixtureState.UPCOMING }.minByOrNull { it.dateMs }
                ?: fixtures.maxByOrNull { it.dateMs }
            vm.selectFixture(pick)
        }
    }

    LaunchedEffect(restoreFocus) {
        if (!restoreFocus) return@LaunchedEffect
        withFrameNanos { }
        val id = selectedFixture?.id
        if (id != null) focusGame(id) else entry()
        onRestored()
    }

    // Settings › Sport leaves the settings screen and lands here with the dialog already open, so the
    // league/language/AI rows are one press from the settings root instead of a long press on a coin.
    LaunchedEffect(openSettingsOnStart) {
        if (openSettingsOnStart) {
            settingsTab = 0
            showSettings = true
            onSettingsOpened()
        }
    }

    val scrolled by remember { derivedStateOf { listState.firstVisibleItemIndex > 0 || listState.firstVisibleItemScrollOffset > 24 } }
    LaunchedEffect(scrolled) { onContentScrolled(scrolled) }

    // A different (or no) game selected: drop the previous game's in-pane preview stream. Tracked as a
    // change, NOT a dispose handler, on purpose — going full-screen hides the whole browse UI and disposes
    // this screen, and stopping here would abort the very channel that was just started (the trap
    // LiveScreen documents). The null sentinel keeps a fresh composition (first entry, or back from the
    // full-screen/mini player) from stopping the stream that is still playing.
    var previewedFixtureId by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(selectedFixture?.id) {
        val id = selectedFixture?.id
        if (previewedFixtureId != null && previewedFixtureId != id) liveVm.stopPreview()
        previewedFixtureId = id
    }

    BoxWithConstraints(modifier.fillMaxSize().onFocusChanged { if (it.hasFocus) onChildFocused() }) {
        val screenW = maxWidth
        fun fx(px: Int) = screenW * (px / 1920f)
        Row(
            Modifier
                .padding(start = contentStart + 20.mpx, top = 52.mpx, end = 28.mpx)
                .fillMaxSize(),
        ) {
            // Sport rail: the app's only rail, drawn like the old Stage capsule — one centred glass
            // column off the screen edge, coins scrolling inside it, Settings pinned at its foot.
            Box(
                Modifier.width(fx(164)).fillMaxHeight(),
                contentAlignment = Alignment.CenterStart,
            ) {
                Column(
                    Modifier
                        .width(fx(140))
                        .heightIn(max = 940.mpx)
                        .stageGlass(42.mpx, overContent = true)
                        .padding(start = 6.mpx, end = 6.mpx, top = 14.mpx, bottom = 20.mpx),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Column(
                        Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(10.mpx),
                    ) {
                        // All: the explicit first coin — star glyph, "All" label, always leading.
                        SportCoin(
                            label = stringResource(R.string.sport_all),
                            glyph = null,
                            selected = favouritesSelected && selectedSportId == SportsViewModel.SportAllId,
                            liveCount = 0,
                            onClick = { vm.selectSport(SportsViewModel.SportAllId) },
                            onLongClick = { settingsTab = 0; showSettings = true },
                            modifier = Modifier
                                .focusRequester(sportRequester(SportsViewModel.SportAllId))
                                .then(if (entryFocusRequester != null) Modifier.focusRequester(entryFocusRequester) else Modifier),
                        )
                        sportCoins.forEach { coin ->
                            SportCoin(
                                label = sportCoinLabel(coin.espnSport),
                                glyph = sportGlyph(coin.espnSport),
                                selected = favouritesSelected && selectedSportId == coin.espnSport,
                                liveCount = coin.liveCount,
                                onClick = { vm.selectSport(coin.espnSport) },
                                onLongClick = { settingsTab = 0; showSettings = true },
                                modifier = Modifier.focusRequester(sportRequester(coin.espnSport)),
                            )
                        }
                    }
                    Box(
                        Modifier
                            .padding(vertical = 10.mpx)
                            .width(34.mpx)
                            .height(1.mpx)
                            .background(StageColors.Ink.copy(alpha = 0.12f)),
                    )
                    // Settings: pinned to the capsule's foot, focusable even when it holds no coins.
                    // OK opens the app's full settings; a long press opens the sport settings.
                    SportCoin(
                        label = stringResource(R.string.sport_settings),
                        glyph = null,
                        icon = OwnTVIcon.SETTINGS,
                        selected = false,
                        liveCount = 0,
                        onClick = { onOpenAppSettings() },
                        onLongClick = { settingsTab = 0; showSettings = true },
                        modifier = Modifier.focusRequester(settingsFocus),
                    )
                }
            }
            // Fixtures (extra top padding like the detail pane: shell clock/weather overlays the top).
            Column(Modifier.weight(1.35f).fillMaxHeight().padding(start = 20.mpx, end = 20.mpx, top = 64.mpx)) {
                // Live | Today | Upcoming filter the list right below them; Refresh re-fetches the board.
                Row(
                    horizontalArrangement = Arrangement.spacedBy(10.mpx),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    FixtureTabPill(stringResource(R.string.sport_tab_live), tab == SportsViewModel.FixtureTab.LIVE) {
                        vm.setTab(SportsViewModel.FixtureTab.LIVE)
                    }
                    FixtureTabPill(stringResource(R.string.sport_today_word), tab == SportsViewModel.FixtureTab.TODAY) {
                        vm.setTab(SportsViewModel.FixtureTab.TODAY)
                    }
                    FixtureTabPill(stringResource(R.string.sport_tab_upcoming), tab == SportsViewModel.FixtureTab.UPCOMING) {
                        vm.setTab(SportsViewModel.FixtureTab.UPCOMING)
                    }
                    RefreshPill(
                        onClick = { vm.refreshSelected() },
                        modifier = Modifier.focusRequester(refreshFocus),
                    )
                }
                // Games are shown without a playlist; only the channels need one. The strip never
                // takes the only focus stop — the fixture rows below stay reachable.
                if (playlists.isEmpty() && fixtures.isNotEmpty()) {
                    NoPlaylistStrip(
                        onAddPlaylist = {
                            settingsTab = 1
                            showSettings = true
                        },
                        modifier = Modifier.padding(top = 12.mpx),
                    )
                }
                when {
                    boardLoading && fixtures.isEmpty() -> {
                        Box(Modifier.fillMaxWidth().padding(top = 60.mpx), contentAlignment = Alignment.Center) {
                            Text(stringResource(R.string.sport_loading), style = stageText(22, 500), color = StageColors.Muted)
                        }
                    }
                    boardError && fixtures.isEmpty() -> {
                        Column(Modifier.fillMaxWidth().padding(top = 60.mpx), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.mpx)) {
                            Text(stringResource(R.string.sport_load_failed), style = stageText(22, 500), color = StageColors.Muted)
                            Row(horizontalArrangement = Arrangement.spacedBy(12.mpx)) {
                                StagePill(stringResource(R.string.sport_retry), onClick = { vm.refreshSelected() }, tinted = true)
                                StagePill(
                                    stringResource(R.string.sport_action_open_settings),
                                    onClick = {
                                        settingsTab = 0
                                        showSettings = true
                                    },
                                )
                            }
                        }
                    }
                    fixtures.isEmpty() -> {
                        Box(Modifier.fillMaxWidth().padding(top = 60.mpx), contentAlignment = Alignment.Center) {
                            Text(stringResource(R.string.sport_no_games), style = stageText(22, 500), color = StageColors.Muted)
                        }
                    }
                    visibleFixtures.isEmpty() -> {
                        Column(
                            Modifier.fillMaxWidth().padding(top = 60.mpx),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(12.mpx),
                        ) {
                            Text(stringResource(emptyTabLabel(tab)), style = stageText(22, 500), color = StageColors.Muted)
                            StagePill(
                                stringResource(R.string.sport_action_show_upcoming),
                                onClick = { vm.setTab(SportsViewModel.FixtureTab.UPCOMING) },
                                tinted = true,
                            )
                        }
                    }
                    else -> {
                        LazyColumn(state = listState, verticalArrangement = Arrangement.spacedBy(10.mpx), modifier = Modifier.fillMaxHeight().padding(top = 12.mpx)) {
                            itemsIndexed(hubRows, key = { _, row -> hubRowKey(row) }) { _, row ->
                                when (row) {
                                    is HubItem.Day -> DayBand(row)
                                    is HubItem.League -> LeagueBand(row) { foldedLeagues[row.leagueId] = row.folded.not() }
                                    is HubItem.Game -> {
                                        val selected = row.fixture.id == selectedFixture?.id
                                        // A live game with a confident best match: OK starts that channel
                                        // right away (no dialog); everything else only selects the game.
                                        val chip = liveMatches[row.fixture.id]
                                        val zapTitle = fixtureZapTitle(row.fixture)
                                        StageSurface(
                                            onClick = {
                                                if (chip != null && isPlayableMatch(chip)) {
                                                    vm.playMatch(chip, listOf(chip)) { id, entities -> onPlayChannel(id, entities, zapTitle) }
                                                } else {
                                                    vm.selectFixture(row.fixture)
                                                }
                                            },
                                            radius = 18.mpx,
                                            modifier = Modifier.fillMaxWidth().focusRequester(gameRequester(row.fixture.id)),
                                            idle = if (selected) Modifier.background(stageAccent.accent.copy(alpha = 0.14f), RoundedCornerShape(18.mpx)) else Modifier,
                                        ) { focused ->
                                            FixtureCard(
                                                row.fixture,
                                                followedTeams,
                                                focused,
                                                selected,
                                                chip = chip,
                                                chipChecked = liveMatches.containsKey(row.fixture.id),
                                            )
                                        }
                                    }
                                }
                            }
                            item(key = "end-spacer") { Spacer(Modifier.height(40.mpx)) }
                        }
                    }
                }
            }
            // Detail pane (extra top padding: shell clock/weather overlays the top-right).
            Column(Modifier.weight(1f).fillMaxHeight().padding(top = 64.mpx)) {
                val fixture = selectedFixture
                if (fixture == null) {
                    Text(stringResource(R.string.sport_details), style = stageText(30, 700), color = StageColors.Text, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(bottom = 12.mpx))
                    Text(stringResource(R.string.sport_no_games), style = stageText(20, 500), color = StageColors.Muted)
                } else {
                    val zapTitle = fixtureZapTitle(fixture)
                    FixtureDetail(
                        fixture = fixture,
                        followedTeams = followedTeams,
                        followedPlayers = followedPlayers,
                        matchesState = matchesState,
                        preferredLanguages = preferredLanguages,
                        standings = standings,
                        standingsLoading = standingsLoading,
                        matchRequester = ::matchRequester,
                        onPlayMatch = { match, list -> vm.playMatch(match, list) { id, entities -> onPlayChannel(id, entities, zapTitle) } },
                        onToggleTeam = { leagueId, abbrev -> vm.toggleTeamFollowed(leagueId, abbrev) },
                        onTogglePlayer = { leagueId, playerId -> vm.togglePlayerFollowed(leagueId, playerId) },
                        hasAiKey = aiEnabled && deepSeekApiKey.isNotBlank(),
                        aiState = aiState,
                        playlistAiState = playlistAiState,
                        aiCountries = aiCountries,
                        aiListSelected = aiListSelected,
                        classicAiSelected = classicAiSelected,
                        aiEnabled = aiEnabled,
                        onAiListSelected = { vm.setAiListSelected(it) },
                        onClassicAiSelected = { vm.setClassicAiSelected(it) },
                        onRequestPlaylistAi = {
                            if (deepSeekApiKey.isBlank()) showAiKeyMissing = true else showPlaylistAiConsent = true
                        },
                        onOpenAiCountries = {
                            if (deepSeekApiKey.isBlank()) {
                                showAiKeyMissing = true
                            } else {
                                vm.setAiListSelected(true)
                                showAiCountries = true
                            }
                        },
                        onCancelAi = { vm.clearAiRecommendation() },
                        onRetryAi = {
                            val countries = (aiState as? SportsViewModel.AiState.Content)
                                ?.countries
                                ?.takeIf { it.isNotEmpty() }
                                ?: aiCountries
                            vm.recommendWithAi(fixture, countries)
                        },
                        onOpenAiSettings = {
                            settingsTab = AiSettingsTab
                            showSettings = true
                        },
                        onPlayAi = { suggestion, suggestions ->
                            vm.playAiSuggestion(suggestion, suggestions) { id, entities ->
                                onPlayChannel(id, entities, zapTitle)
                            }
                        },
                        channelLimit = channelLimit,
                        sportsVm = vm,
                        liveVm = liveVm,
                        effectivePreview = effectivePreview,
                        previewArmed = previewArmed,
                    )
                }
            }
        }
        if (showSettings) {
            SportsSettingsDialog(
                leagues = SportsLeagues,
                active = activeLeagues,
                channelLimit = channelLimit,
                excluded = excludedLanguages,
                deepSeekApiKey = deepSeekApiKey,
                aiSuggestionLimit = aiSuggestionLimit,
                aiEnabled = aiEnabled,
                initialTab = settingsTab,
                remoteState = remoteState,
                remoteApiKeys = vm.remoteApiKeys,
                onDecreaseLimit = { vm.setChannelLimit(channelLimit - 1) },
                onIncreaseLimit = { vm.setChannelLimit(channelLimit + 1) },
                onDecreaseAiLimit = { vm.setAiSuggestionLimit(aiSuggestionLimit - 1) },
                onIncreaseAiLimit = { vm.setAiSuggestionLimit(aiSuggestionLimit + 1) },
                onSetDeepSeekApiKey = { vm.setDeepSeekApiKey(it) },
                onStartRemoteApiKey = { vm.startRemoteApiKeyListener(CompanionLink.DEFAULT_PORT) },
                onStopRemoteApiKey = { vm.stopRemoteListener() },
                onToggleExclude = { vm.toggleExcludedLanguage(it) },
                preferCountry = preferredCountry,
                onPreferCountry = { vm.setPreferredCountry(it) },
                playlists = playlists,
                preferredPlaylistId = preferredPlaylistId,
                onPreferPlaylist = { vm.setPreferredPlaylist(it) },
                onToggleLeague = { vm.toggleLeagueFollowed(it) },
                previewOn = livePreviewSetting,
                onTogglePreview = { liveVm.setLivePreviewEnabled(!livePreviewSetting) },
                onOpenLanguage = {
                    showSettings = false
                    showLanguage = true
                },
                onManageOnPhone = {
                    showSettings = false
                    showPhone = true
                    vm.startPhonePicker(
                        CompanionLink.DEFAULT_PORT,
                        phoneTitle,
                        phoneLeagueItems,
                        phoneLeagueLabels,
                        phoneTeamGroup,
                        phonePlayerGroup,
                    )
                },
                onOpenAppSettings = {
                    showSettings = false
                    onOpenAppSettings()
                },
                onDismiss = { showSettings = false },
            )
        }
        if (showLanguage) {
            SportLanguageDialog(
                selected = preferredLanguages,
                onChange = { vm.setPreferredLanguages(it) },
                onDismiss = {
                    showLanguage = false
                    vm.markLanguageAsked()
                },
            )
        }
        if (showPhone) {
            SportsPhoneDialog(
                state = remoteState,
                onDismiss = {
                    showPhone = false
                    vm.stopPhonePicker()
                },
            )
        }
        if (showAiKeyMissing) {
            StageConfirm(
                title = stringResource(R.string.sport_ai_key_missing_title),
                body = stringResource(R.string.sport_ai_error_no_key),
                confirm = stringResource(R.string.sport_ai_open_settings),
                onConfirm = {
                    showAiKeyMissing = false
                    settingsTab = AiSettingsTab
                    showSettings = true
                },
                onCancel = { showAiKeyMissing = false },
            )
        }
        if (showAiCountries) {
            val fixture = selectedFixture
            if (fixture != null) {
                AiCountryPicker(
                    selected = (aiState as? SportsViewModel.AiState.Content)
                        ?.countries
                        ?.takeIf { it.isNotEmpty() }
                        ?: aiCountries,
                    onConfirm = { countries ->
                        showAiCountries = false
                        vm.recommendWithAi(fixture, countries)
                    },
                    onDismiss = { showAiCountries = false },
                )
            }
        }
        if (showPlaylistAiConsent) {
            val fixture = selectedFixture
            if (fixture != null) {
                StageConfirm(
                    title = stringResource(R.string.sport_ai_playlist_consent_title),
                    body = stringResource(R.string.sport_ai_playlist_consent_body),
                    confirm = stringResource(R.string.sport_ai_playlist_consent_confirm),
                    onConfirm = {
                        showPlaylistAiConsent = false
                        vm.analyzePlaylistWithAi(fixture)
                    },
                    onCancel = { showPlaylistAiConsent = false },
                )
            }
        }
    }
}

/** The fixtures shown for [tab]: Live / Upcoming (soonest first) / Past (most recent first, bounded). */
private fun fixturesFor(fixtures: List<Fixture>, tab: SportsViewModel.FixtureTab): List<Fixture> = when (tab) {
    SportsViewModel.FixtureTab.LIVE -> fixtures.filter { it.state == FixtureState.LIVE }.sortedBy { it.dateMs }
    SportsViewModel.FixtureTab.TODAY -> fixtures.filter { isToday(it, ZoneId.systemDefault()) }.sortedBy { it.dateMs }
    SportsViewModel.FixtureTab.UPCOMING -> fixtures.filter { it.state == FixtureState.UPCOMING }.sortedBy { it.dateMs }
    SportsViewModel.FixtureTab.PAST -> fixtures.filter { it.state == FixtureState.FINISHED }.sortedByDescending { it.dateMs }.take(30)
}

/** Every fixture that belongs to today (in play or kicking off later). */
private fun isToday(fixture: Fixture, zone: ZoneId): Boolean =
    dayKeyOf(fixture, zone, LocalDate.now(zone)) == LocalDate.now(zone)

/**
 * The band day of a fixture: its (local) start date, live fixtures that started earlier (a match
 * running past midnight, a race weekend stage) pinned to today so no stale past-date band shows.
 */
private fun dayKeyOf(fixture: Fixture, zone: ZoneId, today: LocalDate): LocalDate {
    val date = Instant.ofEpochMilli(fixture.dateMs).atZone(zone).toLocalDate()
    return if (fixture.state == FixtureState.LIVE && date.isBefore(today)) today else date
}

/** A fixtures-column row: a date band, a LeagueBand toggle, or a game. */
private sealed interface HubItem {
    data class Day(val date: LocalDate, val live: Int, val games: Int) : HubItem
    data class League(val leagueId: String, val day: LocalDate, val live: Int, val games: Int, val folded: Boolean) : HubItem
    data class Game(val fixture: Fixture) : HubItem
}

private fun hubRowKey(item: HubItem): String = when (item) {
    is HubItem.Day -> "day-${item.date}"
    is HubItem.League -> "lg-${item.leagueId}-${item.day}"
    is HubItem.Game -> "game-${item.fixture.id}"
}

/**
 * The fixtures list rebuilt as Matchday-style rows: date bands (Today / Tomorrow / dated), inside
 * an aggregate one LeagueBand per league (with fold state), games at their start times otherwise.
 */
private fun buildHubRows(
    fixtures: List<Fixture>,
    aggregate: Boolean,
    folded: Map<String, Boolean>,
    zone: ZoneId,
    today: LocalDate,
): List<HubItem> {
    val rows = mutableListOf<HubItem>()
    val byDay = fixtures.groupBy { dayKeyOf(it, zone, today) }.toSortedMap(compareBy { day: LocalDate -> day })
    byDay.forEach { (day, dayGames) ->
        val live = dayGames.count { it.state == FixtureState.LIVE }
        rows.add(HubItem.Day(day, live, dayGames.size))
        if (aggregate) {
            dayGames.groupBy { it.leagueId }.forEach { (leagueId, games) ->
                rows.add(HubItem.League(leagueId, day, games.count { it.state == FixtureState.LIVE }, games.size, folded[leagueId] == true))
                if (folded[leagueId] != true) {
                    games.sortedBy { it.dateMs }.forEach { rows.add(HubItem.Game(it)) }
                }
            }
        } else {
            dayGames.sortedBy { it.dateMs }.forEach { rows.add(HubItem.Game(it)) }
        }
    }
    return rows
}

/** Empty-state text for [tab]. */
private fun emptyTabLabel(tab: SportsViewModel.FixtureTab): Int = when (tab) {
    SportsViewModel.FixtureTab.LIVE -> R.string.sport_no_live_games
    SportsViewModel.FixtureTab.TODAY -> R.string.sport_no_games
    SportsViewModel.FixtureTab.UPCOMING -> R.string.sport_no_upcoming_games
    SportsViewModel.FixtureTab.PAST -> R.string.sport_no_past_games
}

/** Uppercase band label: Today / Tomorrow / Yesterday / the dated form. */
@Composable
private fun dayBandLabel(date: LocalDate): String {
    val today = LocalDate.now(ZoneId.systemDefault())
    val text = when (date) {
        today -> stringResource(R.string.sport_today_word)
        today.plusDays(1) -> stringResource(R.string.sport_tomorrow_word)
        today.minusDays(1) -> stringResource(R.string.sport_yesterday_word)
        else -> date.format(DateTimeFormatter.ofPattern(DayPattern))
    }
    return text.uppercase()
}

/** A date band: the day (accent when it holds live games), a rule and the game count. */
@Composable
private fun DayBand(item: HubItem.Day, modifier: Modifier = Modifier) {
    val labelColor = if (item.live > 0) stageAccent.accent else StageColors.Muted
    Row(
        modifier.fillMaxWidth().padding(top = 8.mpx, start = 2.mpx, end = 6.mpx),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.mpx),
    ) {
        Text(dayBandLabel(item.date), style = stageText(19, 800), color = labelColor, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Box(Modifier.weight(1f).height(2.mpx).background(StageColors.Dim.copy(alpha = 0.4f), RoundedCornerShape(1.mpx)))
        Text(
            item.games.toString(),
            style = stageText(16, 600),
            color = StageColors.Dim,
            maxLines = 1, overflow = TextOverflow.Ellipsis,
        )
    }
}

/** A league band inside one day: league name + counts, a click folds/unfolds its rows. */
@Composable
private fun LeagueBand(item: HubItem.League, modifier: Modifier = Modifier, onToggle: () -> Unit) {
    val accent = stageAccent
    StageSurface(onClick = onToggle, radius = 14.mpx, modifier = modifier.fillMaxWidth()) { focused ->
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 14.mpx, vertical = 8.mpx),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.mpx),
        ) {
            OwnTVIcon(OwnTVIcon.SPORT, if (focused) accent.onAccent else accent.accent, Modifier.size(22.mpx))
            Text(
                leagueLabel(item.leagueId),
                style = stageText(19, 700),
                color = if (focused) accent.onAccent else StageColors.Text,
                maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f),
            )
            if (item.live > 0) LiveDot(item.live.toString())
            Text(item.games.toString(), style = stageText(16, 600), color = StageColors.Dim, maxLines = 1, overflow = TextOverflow.Ellipsis)
            OwnTVIcon(
                if (item.folded) OwnTVIcon.CHEVRON_DOWN else OwnTVIcon.CHEVRON_UP,
                if (focused) accent.onAccent else StageColors.Dim,
                Modifier.size(20.mpx),
            )
        }
    }
}

@Composable
private fun leagueLabel(leagueId: String): String =
    SportsLeagues.firstOrNull { it.id == leagueId }?.let { stringResource(it.labelRes) } ?: leagueId

/**
 * A rail coin (Matchday sidebar): round, emoji glyph (or the star for All, or [icon]), label below,
 * live badge. A focused coin is filled with the accent; a selected-but-unfocused one keeps a 14 %
 * accent fill and takes the accent colour for glyph and label.
 */
@Composable
private fun SportCoin(
    label: String,
    glyph: String?,
    selected: Boolean,
    liveCount: Int,
    onClick: () -> Unit,
    onLongClick: (() -> Unit)?,
    modifier: Modifier = Modifier,
    icon: OwnTVIcon? = null,
) {
    val accent = stageAccent
    // Read here so the label outside the surface follows the same focus state as the fill.
    var focused by remember { mutableStateOf(false) }
    val markColor = when {
        focused -> accent.onAccent
        selected -> accent.accent
        else -> StageColors.NavText
    }
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Box(contentAlignment = Alignment.TopEnd) {
            StageSurface(
                onClick = onClick,
                onLongClick = onLongClick,
                radius = CoinPx.mpx,
                modifier = Modifier.onFocusChanged { focused = it.isFocused },
                idle = if (selected) Modifier.background(accent.accent.copy(alpha = SelectedFillAlpha), RoundedCornerShape(CoinPx.mpx)) else Modifier,
            ) { surfaceFocused ->
                Box(
                    Modifier.size(CoinPx.mpx),
                    contentAlignment = Alignment.Center,
                ) {
                    when {
                        icon != null -> OwnTVIcon(
                            icon,
                            if (surfaceFocused) accent.onAccent else markColor,
                            Modifier.size(26.mpx),
                        )
                        glyph == null -> OwnTVIcon(
                            OwnTVIcon.STAR,
                            if (surfaceFocused) accent.onAccent else markColor,
                            Modifier.size(26.mpx),
                        )
                        else -> Text(
                            glyph,
                            style = stageText(CoinGlyphPx, 400),
                            color = if (surfaceFocused) accent.onAccent else markColor,
                        )
                    }
                }
            }
            if (liveCount > 0 && !selected) {
                LiveDot(liveCount.toString())
            }
        }
        Text(
            label,
            style = stageText(15, 600),
            color = when {
                // The label sits UNDER the tile, outside the focused fill: onAccent would paint it
                // dark on the dark backdrop. Light text there, accent only for the selected state.
                focused -> StageColors.Text
                selected -> accent.accent
                else -> StageColors.Muted
            },
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 4.mpx),
        )
    }
}

/** Translated label per ESPN sport slug for the sidebar coins. */
@Composable
private fun sportCoinLabel(espnSport: String): String = stringResource(
    when (espnSport) {
        "soccer" -> R.string.sport_coin_football
        "tennis" -> R.string.sport_coin_tennis
        "basketball" -> R.string.sport_coin_basketball
        "golf" -> R.string.sport_coin_golf
        "football" -> R.string.sport_coin_gridiron
        "baseball" -> R.string.sport_coin_baseball
        "hockey" -> R.string.sport_coin_hockey
        "racing" -> R.string.sport_coin_racing
        "mma" -> R.string.sport_coin_mma
        "cricket" -> R.string.sport_coin_cricket
        "volleyball" -> R.string.sport_coin_volleyball
        "rugby", "rugby-league", "australian-football" -> R.string.sport_coin_rugby
        else -> R.string.sport_coin_other
    },
)

/** Emoji per ESPN sport slug for the sidebar coins. */
private fun sportGlyph(espnSport: String): String = when (espnSport) {
    "soccer" -> "⚽"
    "tennis" -> "🎾"
    "basketball" -> "🏀"
    "golf" -> "⛳"
    "football" -> "🏈"
    "baseball" -> "⚾"
    "hockey" -> "🏒"
    "racing" -> "🏎️"
    "mma" -> "🥊"
    "cricket" -> "🏏"
    "volleyball" -> "🏐"
    "rugby", "rugby-league", "australian-football" -> "🏉"
    else -> "🏆"
}

private const val CoinPx = 64
private const val CoinGlyphPx = 28

/** Brand red for "live now" (#FF5A5F); the accent green never marks a live state. */
private val LiveRed = Color(0xFFFF5A5F)

/** Idle fill of a selected-but-unfocused rail coin or row (accent at 14 %). */
private const val SelectedFillAlpha = 0.14f

/** One pill in the fixtures column: filled with the accent when selected (Matchday style). */
@Composable
private fun FixtureTabPill(label: String, selected: Boolean, onClick: () -> Unit) {
    val accent = stageAccent
    StageSurface(
        onClick = onClick,
        radius = 20.mpx,
        idle = if (selected) Modifier.background(accent.accent, RoundedCornerShape(20.mpx)) else Modifier,
    ) { focused ->
        Text(
            label,
            style = stageText(18, if (selected) 700 else 500),
            color = if (selected) accent.onAccent else if (focused) accent.accent else StageColors.Text,
            maxLines = 1, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(horizontal = 18.mpx, vertical = 9.mpx),
        )
    }
}

/** The refresh pill at the end of the filter row: Re-fetches the selected board. */
@Composable
private fun RefreshPill(onClick: () -> Unit, modifier: Modifier = Modifier) {
    val accent = stageAccent
    StageSurface(onClick = onClick, radius = 20.mpx, modifier = modifier) { focused ->
        Row(
            Modifier.padding(horizontal = 18.mpx, vertical = 9.mpx),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.mpx),
        ) {
            OwnTVIcon(OwnTVIcon.REFRESH, if (focused) accent.onAccent else StageColors.Text, Modifier.size(18.mpx))
            Text(
                stringResource(R.string.sport_refresh),
                style = stageText(18, 500),
                color = if (focused) accent.onAccent else StageColors.Text,
                maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/** The "no playlist yet" strip above the list: games still show, only the channels need a playlist. */
@Composable
private fun NoPlaylistStrip(onAddPlaylist: () -> Unit, modifier: Modifier = Modifier) {
    Row(
        modifier
            .fillMaxWidth()
            .background(StageColors.ControlFill, RoundedCornerShape(14.mpx))
            .padding(horizontal = 14.mpx, vertical = 10.mpx),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.mpx),
    ) {
        Text(
            stringResource(R.string.sport_no_playlist),
            style = stageText(16, 500),
            color = StageColors.Muted,
            maxLines = 2, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        StagePill(stringResource(R.string.sport_action_add_playlist), onClick = onAddPlaylist, tinted = true)
    }
}

/**
 * The live badge: brand red (#FF5A5F), white text and a 6 px dot. The dot pulses only with the
 * animation level at FULL — the same setting the shell's ambient glow reads.
 */
@Composable
private fun LiveDot(count: String) {
    val animationLevel by koinInject<tv.own.owntv.core.settings.SettingsRepository>().animationLevel
        .collectAsStateWithLifecycle(initialValue = tv.own.owntv.core.theme.AnimationLevel.FULL)
    val transition = if (animationLevel == tv.own.owntv.core.theme.AnimationLevel.FULL) {
        rememberInfiniteTransition()
    } else {
        null
    }
    val dotAlpha = transition
        ?.animateFloat(
            initialValue = 0.35f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(tween(900), RepeatMode.Reverse),
        )
        ?.value ?: 1f
    Row(
        Modifier
            .background(LiveRed, RoundedCornerShape(12.mpx))
            .padding(horizontal = 10.mpx, vertical = 4.mpx),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.mpx),
    ) {
        Box(Modifier.size(6.mpx).background(Color.White.copy(alpha = dotAlpha), RoundedCornerShape(3.mpx)))
        Text(count, style = stageText(15, 700), color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun TeamLogo(url: String?, size: Dp) {
    if (url.isNullOrBlank()) {
        Spacer(Modifier.size(size))
    } else {
        AsyncImage(model = url, contentDescription = null, contentScale = ContentScale.Fit, modifier = Modifier.size(size))
    }
}

/**
 * A club's 6-px bar in a theme-safe tone: ESPN's raw `colorHex` can be near-black and would vanish
 * on the dark page, so the hue is bucketed into [SportioPalette.Team] and the theme-correct tone
 * of the pair is used (brand guide §5.6). Team colours never leak into buttons, focus or text.
 */
@Composable
private fun teamColor(hex: String?): Color? {
    val clean = hex?.takeIf { it.isNotBlank() }?.removePrefix(HashPrefix) ?: return null
    val argb = runCatching { (ColorPrefix + clean).toLong(16) }.getOrNull()?.toInt() ?: return null
    val hsv = FloatArray(3)
    android.graphics.Color.colorToHSV(argb, hsv)
    val (hue, sat, value) = Triple(hsv[0], hsv[1], hsv[2])
    val pair = if (sat < 0.18f || value < 0.12f || (sat < 0.3f && value > 0.85f)) {
        SportioPalette.Team.Slate
    } else when (hue) {
        in 12f..38f -> SportioPalette.Team.Orange
        in 38f..66f -> SportioPalette.Team.Amber
        in 66f..150f -> SportioPalette.Team.Green
        in 150f..185f -> SportioPalette.Team.Sky
        in 185f..235f -> SportioPalette.Team.Blue
        in 235f..265f -> SportioPalette.Team.Indigo
        in 265f..300f -> SportioPalette.Team.Violet
        in 300f..345f -> SportioPalette.Team.Crimson
        else -> SportioPalette.Team.Crimson
    }
    return Color(teamColor(pair, !LocalStagePalette.current.isLight))
}

@Composable
private fun FixtureCard(
    fixture: Fixture,
    followedTeams: Set<String>,
    focused: Boolean,
    selected: Boolean,
    /** Best channel match of this fixture; null once checked means nothing matched. */
    chip: ChannelMatch? = null,
    /** The best-match lookup has already returned for this fixture (chip present or not). */
    chipChecked: Boolean = false,
) {
    val accent = stageAccent
    val titleColor = if (focused) accent.onAccent else StageColors.Text
    val dimColor = if (focused) accent.onAccent.copy(alpha = 0.8f) else StageColors.Dim
    // League bands (sport-in-ago) are not drawn per row, so the row carries the whole matchup.
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.mpx, vertical = 12.mpx),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.mpx),
    ) {
        // Left rail: the start time, or the LIVE flag when the game is running.
        Column(Modifier.width(76.mpx), horizontalAlignment = Alignment.CenterHorizontally) {
            if (fixture.state == FixtureState.LIVE) {
                LiveDot("LIVE")
            } else {
                Text(
                    fixture.statusLine,
                    style = stageText(20, 600),
                    color = dimColor,
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
            }
        }
        Box(Modifier.width(5.mpx).height(52.mpx).background(teamColor(fixture.home?.colorHex) ?: Color.Transparent, RoundedCornerShape(3.mpx)))
        Column(Modifier.weight(1f)) {
            val home = fixture.home
            val away = fixture.away
            if (home != null && away != null) {
                listOf(home, away).forEach { team ->
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.mpx)) {
                        TeamLogo(team.logoUrl, 32.mpx)
                        Text(team.name, style = stageText(21, 700), color = titleColor, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                        Text(
                            team.score ?: fixture.statusLine,
                            style = stageText(22, 700),
                            color = if (fixture.state == FixtureState.LIVE) accent.accent else titleColor,
                            maxLines = 1, overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            } else if (fixture.format == FixtureFormat.VERSUS) {
                // A duel (tennis/MMA): both athletes on an equal footing, same colour; the winner is
                // bold with a check, never a different colour (names are not scores).
                fixture.entries.take(2).forEach { entry ->
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.mpx)) {
                        TeamLogo(entry.logoUrl, 32.mpx)
                        Text(
                            entry.name,
                            style = stageText(21, if (entry.winner) 700 else 500),
                            color = titleColor,
                            maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f),
                        )
                        entry.detail?.let { detail ->
                            Text(detail, style = stageText(22, 700), color = titleColor, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                        if (entry.winner) OwnTVIcon(OwnTVIcon.CHECK, accent.accent, Modifier.size(18.mpx))
                    }
                }
            } else if (fixture.format == FixtureFormat.EVENT) {
                // A single occasion (F1 Grand Prix): the race name + circuit, no participants at all.
                Text(
                    fixture.title ?: fixture.statusLine,
                    style = stageText(21, 700), color = titleColor, maxLines = 2, overflow = TextOverflow.Ellipsis,
                )
                fixture.venue?.let { venue ->
                    Text(venue, style = stageText(16, 500), color = dimColor, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            } else {
                // A leaderboard (golf): the leader large, the next chasers small.
                Text(
                    fixture.entries.firstOrNull()?.name ?: fixture.statusLine,
                    style = stageText(21, 700), color = titleColor, maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
                fixture.entries.drop(1).take(2).forEach { entry ->
                    Text(entry.name, style = stageText(17, 500), color = dimColor, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            // The day rail carries dates; every non-live row still names its source when it helps.
            if (fixture.state != FixtureState.LIVE && fixture.broadcasts.isNotEmpty() && fixture.state == FixtureState.UPCOMING) {
                Text(
                    fixture.broadcasts.first(),
                    style = stageText(14, 500),
                    color = dimColor,
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 2.mpx),
                )
            }
        }
        // Best matched channel of a live game, right-aligned: name · resolution · source. While the
        // per-fixture lookup runs the row says so; an empty result leaves the row without a chip.
        when {
            chipChecked && chip != null -> Text(
                channelChipText(chip),
                style = stageText(14, 600),
                color = if (focused) accent.onAccent else accent.accent,
                maxLines = 1, overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .widthIn(max = 260.mpx)
                    .background(
                        if (focused) Color.Transparent else accent.accent.copy(alpha = SelectedFillAlpha),
                        RoundedCornerShape(10.mpx),
                    )
                    .padding(horizontal = 10.mpx, vertical = 4.mpx),
            )
            !chipChecked && fixture.state == FixtureState.LIVE -> Text(
                stringResource(R.string.sport_chip_checking),
                style = stageText(14, 500),
                color = dimColor,
                maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
        }
        if (selected && fixture.state != FixtureState.LIVE) {
            OwnTVIcon(OwnTVIcon.CHECK, accent.accent, Modifier.size(22.mpx))
        }
    }
}

@Composable
private fun FixtureChip(fixture: Fixture, followedTeams: Set<String>, focused: Boolean) {
    val accent = stageAccent
    val home = fixture.home
    val away = fixture.away
    val titleColor = if (focused) accent.onAccent else StageColors.Text
    val scoreColor = if (fixture.state == FixtureState.LIVE) accent.accent else if (focused) accent.onAccent else StageColors.Dim
    Row(
        Modifier.padding(horizontal = 14.mpx, vertical = 9.mpx),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.mpx),
    ) {
        if (home != null && away != null) {
            TeamLogo(home.logoUrl, 24.mpx)
            Text(home.abbreviation, style = stageText(17, 700), color = titleColor, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(fixtureScoreLine(fixture), style = stageText(17, 600), color = scoreColor, maxLines = 1, overflow = TextOverflow.Ellipsis)
            TeamLogo(away.logoUrl, 24.mpx)
            Text(away.abbreviation, style = stageText(17, 700), color = titleColor, maxLines = 1, overflow = TextOverflow.Ellipsis)
        } else {
            // A duel/field entry (tennis/MMA/golf): the chip names the athletes, then the status.
            val names = fixture.entries.take(2).joinToString(" ${stringResource(R.string.sport_versus)} ") { it.name }
            Text(names, style = stageText(17, 700), color = titleColor, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(fixture.statusLine, style = stageText(17, 600), color = scoreColor, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
private fun fixtureScoreLine(fixture: Fixture): String {
    val home = fixture.home?.score
    val away = fixture.away?.score
    return if (home != null && away != null) stringResource(R.string.sport_score, home, away) else fixture.statusLine
}

/** Overlay heading for the in-player fixture channel list (roadmap point 6): the matchup, the
 *  event name, or the leader — never blank. Dynamic (team names), so no string resource. */
@Composable
private fun fixtureZapTitle(fixture: Fixture): String {
    val versus = stringResource(R.string.sport_versus)
    val home = fixture.home
    val away = fixture.away
    return when {
        home != null && away != null -> "${home.abbreviation} $versus ${away.abbreviation}"
        fixture.format == FixtureFormat.VERSUS ->
            fixture.entries.take(2).joinToString(" $versus ") { it.name }.ifBlank { fixture.statusLine }
        fixture.format == FixtureFormat.EVENT -> fixture.title ?: fixture.statusLine
        else -> fixture.entries.firstOrNull()?.name ?: fixture.statusLine
    }
}

@Composable
private fun FixtureDetail(
    fixture: Fixture,
    followedTeams: Set<String>,
    followedPlayers: Set<String>,
    matchesState: SportsViewModel.MatchesState,
    preferredLanguages: Set<SportLanguage>,
    standings: List<LeagueTable>,
    standingsLoading: Boolean,
    matchRequester: (Long) -> FocusRequester,
    onPlayMatch: (ChannelMatch, List<ChannelMatch>) -> Unit,
    onToggleTeam: (String, String) -> Unit,
    onTogglePlayer: (String, String) -> Unit,
    hasAiKey: Boolean,
    aiState: SportsViewModel.AiState,
    playlistAiState: SportsViewModel.PlaylistAiState,
    aiCountries: Set<AiCountry>,
    aiListSelected: Boolean,
    classicAiSelected: Boolean,
    /** Whether the AI functions may run; off means the AI tab does not exist. */
    aiEnabled: Boolean,
    onAiListSelected: (Boolean) -> Unit,
    onClassicAiSelected: (Boolean) -> Unit,
    onRequestPlaylistAi: () -> Unit,
    onOpenAiCountries: () -> Unit,
    onCancelAi: () -> Unit,
    onRetryAi: () -> Unit,
    onOpenAiSettings: () -> Unit,
    onPlayAi: (AiSuggestion, List<AiSuggestion>) -> Unit,
    /** How many channel rows the merged list shows (Sport settings). */
    channelLimit: Int,
    sportsVm: SportsViewModel,
    liveVm: LiveViewModel,
    effectivePreview: Boolean,
    previewArmed: Boolean,
) {
    val accent = stageAccent
    // The matcher already filtered by the preferred languages (before its cap) — show them as-is.
    val matches = (matchesState as? SportsViewModel.MatchesState.Content)?.matches.orEmpty()
    val aiContent = aiState as? SportsViewModel.AiState.Content
    val aiSuggestions = aiContent?.suggestions.orEmpty()
    // Channels header toggle: the merged list (default) or the three per-strategy lists.
    var inspectSources by remember(fixture.id) { mutableStateOf(false) }
    // While the AI functions are switched off there is no AI list, and the AI column only exists
    // behind Inspect sources: it is not a view of its own any more.
    val aiVisible = aiEnabled && aiListSelected && inspectSources
    var entities by remember(fixture.id) { mutableStateOf(emptyList<ChannelEntity>()) }
    var aiEntities by remember(fixture.id) { mutableStateOf(emptyList<ChannelEntity>()) }
    var focusedChannelId by remember(fixture.id) { mutableStateOf<Long?>(null) }
    var focusedAiId by remember(fixture.id) { mutableStateOf<Long?>(null) }
    LaunchedEffect(fixture.id, matches) {
        entities = if (matches.isEmpty()) emptyList() else sportsVm.entitiesFor(matches)
        if (focusedChannelId == null) focusedChannelId = matches.firstOrNull()?.channelId
    }
    LaunchedEffect(fixture.id, aiSuggestions) {
        aiEntities = if (aiSuggestions.isEmpty()) emptyList() else sportsVm.entitiesForAi(aiSuggestions)
        when {
            aiSuggestions.isEmpty() -> focusedAiId = null
            aiSuggestions.none { it.channelId == focusedAiId } ->
                focusedAiId = aiSuggestions.first().channelId
        }
    }
    val focusedAiSuggestion = if (aiVisible) {
        aiSuggestions.firstOrNull { it.channelId == focusedAiId }
    } else {
        null
    }
    val aiPreviewEntity = if (aiVisible) {
        aiEntities.firstOrNull { it.id == focusedAiId }
    } else {
        null
    }
    // AI results own the preview only while the AI tab is open; focusing a normal channel clears
    // focusedAiId, and switching back to Classic immediately returns the preview to the matcher.
    val previewEntity = aiPreviewEntity
        ?: entities.firstOrNull { it.id == focusedChannelId }
        ?: entities.firstOrNull()
    val previewEpg = focusedAiSuggestion?.broadcaster
        ?: matches.firstOrNull { it.channelId == previewEntity?.id }?.epgTitle
    LaunchedEffect(previewEntity?.id, effectivePreview, previewArmed) {
        val entity = previewEntity ?: return@LaunchedEffect
        if (!effectivePreview || !previewArmed) return@LaunchedEffect
        delay(700)
        liveVm.playPreview(entity)
    }
    Column(modifier = Modifier.fillMaxHeight()) {
        if (previewEntity != null) {
            GuideVideo(
                channel = previewEntity,
                channelLine = previewEntity.name,
                nowTitle = previewEpg,
                previewEngine = liveVm.previewEngine,
                showVideo = effectivePreview && previewArmed,
                showStreamChips = true, // Sport-Hub preview: quality + fps chips like the LiveStage
                modifier = Modifier.fillMaxWidth().padding(bottom = 8.mpx),
            )
        }
        // Fixed above the scrolling details: the preview and the heading stay visible while the
        // user browses channels or returns from playback.
        Text(
            stringResource(R.string.sport_details),
            style = stageText(30, 700),
            color = StageColors.Text,
            maxLines = 1, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(bottom = 6.mpx),
        )
        LazyColumn(verticalArrangement = Arrangement.spacedBy(10.mpx), modifier = Modifier.weight(1f)) {
        item(key = "detail" + fixture.id) {
            Column(verticalArrangement = Arrangement.spacedBy(8.mpx)) {
                val homeTeam = fixture.home
                val awayTeam = fixture.away
                if (homeTeam != null && awayTeam != null) {
                    TeamScoreRow(homeTeam, fixture.state)
                    TeamScoreRow(awayTeam, fixture.state)
                } else if (fixture.format == FixtureFormat.VERSUS) {
                    // A duel (tennis/MMA): the two athletes as equal sides, same colour; the winner is
                    // bold with a check.
                    fixture.entries.take(2).forEach { entry ->
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.mpx), modifier = Modifier.fillMaxWidth()) {
                            TeamLogo(entry.logoUrl, 40.mpx)
                            Text(
                                entry.name,
                                style = stageText(22, if (entry.winner) 700 else 600),
                                color = StageColors.Text,
                                maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f),
                            )
                            entry.detail?.let { detail ->
                                Text(detail, style = stageText(20, 600), color = StageColors.Dim, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                            if (entry.winner) OwnTVIcon(OwnTVIcon.CHECK, accent.accent, Modifier.size(20.mpx))
                        }
                    }
                } else if (fixture.format == FixtureFormat.EVENT) {
                    // A single occasion (F1 Grand Prix): the race name; the location follows below.
                    Text(
                        fixture.title ?: fixture.statusLine,
                        style = stageText(24, 700), color = StageColors.Text, maxLines = 2, overflow = TextOverflow.Ellipsis,
                    )
                } else {
                    // A leaderboard (golf): rank · athlete · value.
                    fixture.entries.take(10).forEach { entry ->
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.mpx), modifier = Modifier.fillMaxWidth()) {
                            Text(entry.order?.toString().orEmpty(), style = stageText(16, 600), color = StageColors.Dim, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.width(30.mpx))
                            TeamLogo(entry.logoUrl, 26.mpx)
                            Text(
                                entry.name,
                                style = stageText(19, if (entry.winner) 700 else 500),
                                color = if (entry.winner) accent.accent else StageColors.Text,
                                maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f),
                            )
                            entry.detail?.let { detail ->
                                Text(detail, style = stageText(18, 600), color = StageColors.Dim, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                            if (entry.winner) OwnTVIcon(OwnTVIcon.CHECK, accent.accent, Modifier.size(18.mpx))
                        }
                    }
                }
                if (fixture.state != FixtureState.UPCOMING || fixture.statusLine.isNotBlank()) {
                    Text(fixture.statusLine, style = stageText(18, 600), color = if (fixture.state == FixtureState.LIVE) accent.accent else StageColors.Dim, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                fixture.venue?.let { venue ->
                    Text(stringResource(R.string.sport_venue), style = stageText(15, 600), color = StageColors.Dim, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(venue, style = stageText(18, 500), color = StageColors.Text, maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
                if (fixture.broadcasts.isNotEmpty()) {
                    Text(stringResource(R.string.sport_broadcast), style = stageText(15, 600), color = StageColors.Dim, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    fixture.broadcasts.take(4).forEach { name ->
                        Text(name, style = stageText(18, 500), color = StageColors.Text, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
                if (homeTeam != null && awayTeam != null) {
                    Row(horizontalArrangement = Arrangement.spacedBy(10.mpx), modifier = Modifier.fillMaxWidth()) {
                        FollowButton(fixture.leagueId, homeTeam.abbreviation, followedTeams.contains(SportsPrefs.teamKey(fixture.leagueId, homeTeam.abbreviation)), Modifier.weight(1f)) {
                            onToggleTeam(fixture.leagueId, homeTeam.abbreviation)
                        }
                        FollowButton(fixture.leagueId, awayTeam.abbreviation, followedTeams.contains(SportsPrefs.teamKey(fixture.leagueId, awayTeam.abbreviation)), Modifier.weight(1f)) {
                            onToggleTeam(fixture.leagueId, awayTeam.abbreviation)
                        }
                    }
                } else if (fixture.format == FixtureFormat.VERSUS) {
                    // A duel (tennis / MMA): follow the athlete. Deliberately NOT a leaderboard
                    // (golf/F1/racing): following a single driver or a golfer makes no sense.
                    val duelists = fixture.entries.filter { it.id != null }.take(2)
                    if (duelists.isNotEmpty()) {
                        Row(horizontalArrangement = Arrangement.spacedBy(10.mpx), modifier = Modifier.fillMaxWidth()) {
                            duelists.forEach { entry ->
                                val playerId = entry.id ?: return@forEach
                                FollowButton(
                                    fixture.leagueId,
                                    entry.name,
                                    followedPlayers.contains(SportsPrefs.playerKey(fixture.leagueId, playerId)),
                                    Modifier.weight(1f),
                                ) {
                                    onTogglePlayer(fixture.leagueId, playerId)
                                }
                            }
                        }
                    }
                }
            }
        }
        item(key = "channels" + fixture.id) {
            Column(verticalArrangement = Arrangement.spacedBy(8.mpx), modifier = Modifier.padding(top = 8.mpx)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.mpx), modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.sport_channels), style = stageText(22, 700), color = StageColors.Text, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                    // The per-strategy lists (with their refresh) live behind Inspect sources; the
                    // default view is the single merged list.
                    if (inspectSources && !aiVisible) {
                        val refreshing = matchesState == SportsViewModel.MatchesState.Loading
                        StageSurface(
                            onClick = { sportsVm.refreshMatches() },
                            radius = 14.mpx,
                            enabled = !refreshing,
                        ) { focused ->
                            Row(Modifier.padding(horizontal = 12.mpx, vertical = 7.mpx), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.mpx)) {
                                OwnTVIcon(OwnTVIcon.REFRESH, if (focused) accent.onAccent else StageColors.Text, Modifier.size(18.mpx))
                                Text(stringResource(R.string.sport_refresh_channels), style = stageText(16, 600), color = if (focused) accent.onAccent else StageColors.Text, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                        }
                    }
                    StagePill(
                        stringResource(R.string.sport_action_inspect),
                        onClick = {
                            // Leaving the inspection returns to the merged list: the strategy
                            // choice is a property of the inspection, not of the fixture.
                            if (inspectSources) {
                                onAiListSelected(false)
                                onClassicAiSelected(false)
                            }
                            inspectSources = !inspectSources
                        },
                        tinted = inspectSources,
                    )
                }
                // Classic (original guide/EPG matcher), DeepSeek Match (evidence-tiered board
                // pipeline) and AI Suggest (the older name-to-channel recommendation). The AI
                // columns only exist while the AI functions are switched on.
                if (inspectSources) {
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.mpx),
                    ) {
                        StageTab(
                            label = stringResource(R.string.sport_channels_classic),
                            count = "",
                            selected = !aiVisible && !classicAiSelected,
                            onClick = {
                                onClassicAiSelected(false)
                                onAiListSelected(false)
                            },
                            modifier = Modifier.weight(1f),
                        )
                        if (aiEnabled) {
                            StageTab(
                                label = stringResource(R.string.sport_channels_classic_ai),
                                count = "",
                                selected = !aiVisible && classicAiSelected,
                                onClick = {
                                    onClassicAiSelected(true)
                                    onAiListSelected(false)
                                },
                                modifier = Modifier.weight(1f),
                            )
                            StageTab(
                                label = stringResource(R.string.sport_ai_tab),
                                count = "",
                                selected = aiVisible,
                                onClick = { onAiListSelected(true) },
                                modifier = Modifier.weight(1f),
                            )
                        }
                    }
                }
                if (aiVisible) {
                    AiChannelsPanel(
                        state = aiState,
                        selectedCountries = aiContent?.countries?.takeIf { it.isNotEmpty() } ?: aiCountries,
                        hasAiKey = hasAiKey,
                        onOpenCountries = onOpenAiCountries,
                        onCancel = onCancelAi,
                        onRetry = onRetryAi,
                        onOpenSettings = onOpenAiSettings,
                        onPlay = onPlayAi,
                        onFocusSuggestion = { suggestion ->
                            focusedAiId = suggestion.channelId
                            focusedChannelId = null
                            aiEntities.firstOrNull { it.id == suggestion.channelId }?.let { entity ->
                                liveVm.onChannelFocused(entity)
                            }
                        },
                    )
                } else {
                    if (preferredLanguages.isNotEmpty()) {
                        val languageLabels = SportLanguages.order
                            .filter { it in preferredLanguages }
                            .map { stringResource(it.labelRes) }
                        Text(
                            stringResource(R.string.sport_language_filter, languageLabels.joinToString()),
                            style = stageText(14, 500), color = StageColors.Dim, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        )
                    }
                    when (matchesState) {
                        SportsViewModel.MatchesState.Idle -> {
                            Text(stringResource(R.string.sport_loading), style = stageText(17, 500), color = StageColors.Dim)
                        }
                        SportsViewModel.MatchesState.Loading -> {
                            Text(
                                stringResource(if (classicAiSelected) R.string.sport_deepseek_checking else R.string.sport_loading),
                                style = stageText(17, 500),
                                color = StageColors.Dim,
                            )
                        }
                        is SportsViewModel.MatchesState.Content -> {
                            // Default: one merged, deduped list. Inspect sources: the chosen
                            // strategy's own list, unchanged.
                            val list = if (inspectSources) matches else mergeChannels(matches, channelLimit)
                            if (list.isEmpty()) {
                                Text(stringResource(R.string.sport_no_channel), style = stageText(17, 500), color = StageColors.Dim)
                            } else {
                                var possibleHeader = false
                                var conflictingHeader = false
                                list.forEach { match ->
                                    if (!possibleHeader && match.evidenceTier == ChannelEvidenceTier.POSSIBLE) {
                                        possibleHeader = true
                                        EvidenceHeader(stringResource(R.string.sport_evidence_possible))
                                    }
                                    if (!conflictingHeader && match.evidenceTier == ChannelEvidenceTier.CONFLICTING) {
                                        conflictingHeader = true
                                        EvidenceHeader(stringResource(R.string.sport_evidence_conflicting))
                                    }
                                    StageSurface(
                                        onClick = { onPlayMatch(match, list) },
                                        radius = 14.mpx,
                                        modifier = Modifier.fillMaxWidth().focusRequester(matchRequester(match.channelId))
                                            .onFocusChanged {
                                                if (it.hasFocus) {
                                                    focusedAiId = null
                                                    focusedChannelId = match.channelId
                                                    entities.firstOrNull { entity -> entity.id == match.channelId }?.let { entity ->
                                                        liveVm.onChannelFocused(entity)
                                                    }
                                                }
                                            },
                                    ) { focused ->
                                        Row(
                                            Modifier.fillMaxWidth().padding(horizontal = 14.mpx, vertical = 10.mpx),
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.spacedBy(12.mpx),
                                        ) {
                                            OwnTVIcon(OwnTVIcon.PLAY, if (focused) accent.onAccent else accent.accent, Modifier.size(22.mpx))
                                            Column(Modifier.weight(1f)) {
                                                Text(match.channelName, style = stageText(19, 600), color = if (focused) accent.onAccent else StageColors.Text, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                                evidenceLine(match)?.let { title ->
                                                    Text(title, style = stageText(14, 500), color = if (focused) accent.onAccent.copy(alpha = 0.8f) else StageColors.Dim, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
        if (standingsLoading) {
            item(key = "table-loading") {
                Text(stringResource(R.string.sport_loading), style = stageText(17, 500), color = StageColors.Dim, modifier = Modifier.padding(top = 8.mpx))
            }
        }
        standings.forEach { table ->
            item(key = "table" + fixture.leagueId + (table.groupName.orEmpty())) {
                Column(verticalArrangement = Arrangement.spacedBy(6.mpx), modifier = Modifier.padding(top = 8.mpx)) {
                    Text(stringResource(R.string.sport_standings), style = stageText(22, 700), color = StageColors.Text, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    table.groupName?.let { name ->
                        Text(name, style = stageText(15, 600), color = StageColors.Dim, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    table.rows.take(10).forEach { row ->
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.mpx), modifier = Modifier.fillMaxWidth()) {
                            Text(row.rank, style = stageText(16, 600), color = StageColors.Dim, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.width(30.mpx))
                            TeamLogo(row.logoUrl, 26.mpx)
                            Text(row.teamAbbrev.ifBlank { row.teamName }, style = stageText(17, 600), color = StageColors.Text, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                            row.stats.take(3).forEach { stat ->
                                Text(stat, style = stageText(16, 500), color = StageColors.Dim, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.width(44.mpx))
                            }
                        }
                    }
                }
            }
        }
        item { Spacer(Modifier.height(60.mpx)) }
        }
    }
}

@Composable
private fun EvidenceHeader(text: String) {
    Text(
        text,
        style = stageText(14, 700),
        color = StageColors.Dim,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier.padding(top = 4.mpx),
    )
}

/** Evidence tier and its source, then the audit detail; Classic rows keep their old subtitle. */
@Composable
private fun evidenceLine(match: ChannelMatch): String? {
    val tier = match.evidenceTier
    if (tier == null) {
        return (match.epgTitle ?: match.aiReason)
            ?.takeIf { it.isNotBlank() }
    }
    val tierLabel = when (tier) {
        ChannelEvidenceTier.SUPPORTED -> stringResource(R.string.sport_evidence_supported)
        ChannelEvidenceTier.LIKELY -> stringResource(R.string.sport_evidence_likely)
        ChannelEvidenceTier.POSSIBLE -> stringResource(R.string.sport_evidence_possible)
        ChannelEvidenceTier.CONFLICTING -> stringResource(R.string.sport_evidence_conflicting)
    }
    val source = when (match.evidenceSource) {
        ChannelEvidenceSource.SEARCH -> stringResource(R.string.sport_evidence_search)
        ChannelEvidenceSource.EPG -> stringResource(R.string.sport_evidence_epg)
        ChannelEvidenceSource.RIGHTS -> stringResource(R.string.sport_evidence_rights)
        null -> null
    }
    val head = listOfNotNull(tierLabel, source).joinToString(Separator)
    val detail = match.evidenceDetail ?: match.epgTitle ?: match.aiReason
    return listOfNotNull(head, detail?.takeIf { it.isNotBlank() }).joinToString(Separator)
}

private const val Separator = " · "

/** The row chip of a matched channel: "<channel> · <resolution> · <source>". */
@Composable
private fun channelChipText(match: ChannelMatch): String {
    val source = when {
        match.aiConfirmed || match.aiReason != null -> stringResource(R.string.sport_chip_source_ai)
        match.epgConfirmed || match.epgTitle != null || match.evidenceSource == ChannelEvidenceSource.EPG ->
            stringResource(R.string.sport_chip_source_guide)
        else -> stringResource(R.string.sport_chip_source_name)
    }
    return listOf(match.channelName, qualityLabel(match.channelName), source).joinToString(Separator)
}

/** Resolution word of a channel name, from the matcher's own ranking. */
@Composable
private fun qualityLabel(channelName: String): String = stringResource(
    when {
        ChannelDedup.resolutionRank(channelName) >= 4 -> R.string.sport_quality_4k
        ChannelDedup.resolutionRank(channelName) == 3 -> R.string.sport_quality_fhd
        ChannelDedup.resolutionRank(channelName) == 2 -> R.string.sport_quality_hd
        else -> R.string.sport_quality_sd
    },
)

/**
 * Whether a row's best match is confident enough to start on OK without asking. The evidence tier
 * decides where the matcher produced one; a classic row (no tier) needs an exact name hit or EPG
 * confirmation — a bare name resemblance only selects the game.
 */
private fun isPlayableMatch(match: ChannelMatch?): Boolean = when (match?.evidenceTier) {
    ChannelEvidenceTier.SUPPORTED, ChannelEvidenceTier.LIKELY -> true
    ChannelEvidenceTier.POSSIBLE, ChannelEvidenceTier.CONFLICTING -> false
    null -> match != null && (match.isExact || match.epgConfirmed || match.aiConfirmed)
}

/**
 * The default channel list: one row per channel, confident rows first (Supported and the classic
 * guide/name rows), then Likely, then Possible/Conflicting — Classic order inside each group.
 */
private fun mergeChannels(matches: List<ChannelMatch>, limit: Int): List<ChannelMatch> {
    fun group(match: ChannelMatch): Int = when (match.evidenceTier) {
        ChannelEvidenceTier.LIKELY -> 1
        ChannelEvidenceTier.POSSIBLE, ChannelEvidenceTier.CONFLICTING -> 2
        ChannelEvidenceTier.SUPPORTED, null -> 0
    }
    return matches.distinctBy { it.channelId }.sortedBy { group(it) }.take(limit)
}

/** One "prefer channels from" option; [code] is a [SportLanguages.preferredCountryOptions] key, "" = none. */
private data class CountryPreference(val code: String, @StringRes val labelRes: Int)

private val CountryPreferenceOptions: List<CountryPreference> = listOf(
    CountryPreference("", R.string.sport_prefer_none),
    CountryPreference("UK", R.string.sport_prefer_uk),
    CountryPreference("US", R.string.sport_prefer_us),
    CountryPreference("FR", R.string.sport_prefer_fr),
    CountryPreference("AR", R.string.sport_prefer_ar),
    CountryPreference("DE", R.string.sport_prefer_de),
)

/**
 * Sport settings: four tabs (leagues, channels, languages, AI) instead of one endless list. The
 * preferred languages stay in [SportLanguageDialog] (opened from the languages tab); the phone
 * picker in the leagues tab (it manages leagues, teams and players); the AI tab holds the DeepSeek
 * key and the suggestion count.
 */
@Composable
private fun SportsSettingsDialog(
    leagues: List<SportLeague>,
    active: Set<String>,
    channelLimit: Int,
    excluded: Set<SportLanguage>,
    deepSeekApiKey: String,
    aiSuggestionLimit: Int,
    /** Whether the AI functions may run; off means the key below is stored but unused. */
    aiEnabled: Boolean,
    initialTab: Int,
    remoteState: CompanionServerState,
    remoteApiKeys: Flow<String>,
    onDecreaseLimit: () -> Unit,
    onIncreaseLimit: () -> Unit,
    onDecreaseAiLimit: () -> Unit,
    onIncreaseAiLimit: () -> Unit,
    onSetDeepSeekApiKey: (String) -> Unit,
    onStartRemoteApiKey: () -> Unit,
    onStopRemoteApiKey: () -> Unit,
    onToggleExclude: (SportLanguage) -> Unit,
    /** Preferred channel country ("" = none); see [SportLanguages.preferredCountryOptions]. */
    preferCountry: String,
    onPreferCountry: (String) -> Unit,
    /** Live playlists and the one whose channels rank first (0 = none). */
    playlists: List<SportsViewModel.PlaylistOption>,
    preferredPlaylistId: Long,
    onPreferPlaylist: (Long) -> Unit,
    onToggleLeague: (String) -> Unit,
    /** In-pane preview switch (shared with Settings → video player). */
    previewOn: Boolean,
    onTogglePreview: () -> Unit,
    onOpenLanguage: () -> Unit,
    onManageOnPhone: () -> Unit,
    /** The app's own settings (playlists, profile, EPG, updates) — the nav rail used to carry this door. */
    onOpenAppSettings: () -> Unit,
    onDismiss: () -> Unit,
) {
    val firstFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        delay(60)
        runCatching { firstFocus.requestFocus() }
    }
    BackHandler { onDismiss() }
    var showApiKey by remember { mutableStateOf(false) }
    // The key field is controlled from here so handing over to the phone panel — and coming back from
    // it — never loses what was already typed. Resets when the stored key changes, i.e. after a save.
    var apiKeyDraft by remember(deepSeekApiKey) { mutableStateOf(deepSeekApiKey) }
    var showApiKeyPhone by remember { mutableStateOf(false) }
    var pendingPhoneKey by remember { mutableStateOf<String?>(null) }
    // Both ways back from the phone panel (a key arrived, or the user backed out) land in the key
    // dialog again. The payload is parked and applied in its own effect, so only one focus-trapping
    // popup is ever alive — the phone panel must have left the composition first.
    var reopenKeyDialog by remember { mutableStateOf(false) }
    LaunchedEffect(showApiKeyPhone) {
        if (!showApiKeyPhone) return@LaunchedEffect
        remoteApiKeys.collect { received ->
            pendingPhoneKey = received
            showApiKeyPhone = false
            reopenKeyDialog = true
        }
    }
    LaunchedEffect(reopenKeyDialog) {
        if (!reopenKeyDialog) return@LaunchedEffect
        delay(150)
        reopenKeyDialog = false
        pendingPhoneKey?.let { apiKeyDraft = it }
        pendingPhoneKey = null
        showApiKey = true
    }
    OwnTVPopup(onDismissRequest = onDismiss, stageLayout = true) {
        Box(Modifier.fillMaxSize().background(StageColors.Scrim)) {
            Column(
                Modifier
                    .padding(start = 560.mpx, top = 120.mpx, bottom = 24.mpx)
                    .width(640.mpx)
                    .trapAllFocusExit()
                    .focusGroup(),
            ) {
                var tab by remember { mutableIntStateOf(initialTab.coerceIn(0, AiSettingsTab)) }
                var regionTab by remember { mutableIntStateOf(0) }
                StageMenu(Modifier.verticalScroll(rememberScrollState())) {
                    StageMenuHeader(
                        title = stringResource(R.string.sport_settings),
                        subtitle = null,
                    )
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.mpx),
                    ) {
                        StageTab(
                            label = stringResource(R.string.sport_settings_tab_leagues),
                            count = active.size.toString(),
                            selected = tab == 0,
                            onClick = { tab = 0 },
                            modifier = Modifier.weight(1f).focusRequester(firstFocus),
                        )
                        StageTab(
                            label = stringResource(R.string.sport_settings_tab_channels),
                            count = "",
                            selected = tab == 1,
                            onClick = { tab = 1 },
                            modifier = Modifier.weight(1f),
                        )
                        StageTab(
                            label = stringResource(R.string.sport_settings_tab_languages),
                            count = "",
                            selected = tab == 2,
                            onClick = { tab = 2 },
                            modifier = Modifier.weight(1f),
                        )
                        StageTab(
                            label = stringResource(R.string.sport_ai_tab),
                            count = aiSuggestionLimit.toString(),
                            selected = tab == AiSettingsTab,
                            onClick = { tab = AiSettingsTab },
                            modifier = Modifier.weight(1f),
                        )
                    }
                    when (tab) {
                        0 -> {
                            val locale = androidx.compose.ui.text.intl.Locale.current.platformLocale
                            val chevron = stringResource(R.string.sport_sublevel_chevron)
                            StageMenuItem(
                                text = stringResource(R.string.sport_manage_on_phone),
                                icon = OwnTVIcon.PHONE,
                                value = chevron,
                                onClick = onManageOnPhone,
                            )
                            // Region switcher (top level): globe icon + ‹ n › value set it apart from
                            // the league rows below; Left/Right cycle the region, OK steps forward.
                            StageGroupLabel(stringResource(R.string.sport_region_title).uppercase(locale))
                            val region = RegionOrder[regionTab.coerceIn(RegionOrder.indices)]
                            val regionLeagues = leagues.filter { it.region == region }
                            val regionActive = regionLeagues.count { it.id in active }
                            StageMenuItem(
                                text = stringResource(region.labelRes),
                                value = stringResource(R.string.sport_region_cycler_value, regionActive.toString()),
                                icon = OwnTVIcon.NETWORK,
                                onClick = { regionTab = (regionTab + 1) % RegionOrder.size },
                                modifier = Modifier.onPreviewKeyEvent { event ->
                                    if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                                    when (event.key) {
                                        Key.DirectionLeft -> {
                                            regionTab = (regionTab + RegionOrder.size - 1) % RegionOrder.size
                                            true
                                        }
                                        Key.DirectionRight -> {
                                            regionTab = (regionTab + 1) % RegionOrder.size
                                            true
                                        }
                                        else -> false
                                    }
                                },
                            )
                            Text(
                                stringResource(R.string.sport_region_switch_hint),
                                style = stageText(15, 500),
                                color = StageColors.Dim,
                                maxLines = 1, overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.padding(start = 18.mpx, end = 18.mpx, top = 8.mpx, bottom = 4.mpx),
                            )
                            // Sublevel: the checklist shows only the leagues of the region above.
                            StageGroupLabel(stringResource(region.labelRes).uppercase(locale))
                            regionLeagues.forEach { league ->
                                StageMenuItem(
                                    text = stringResource(league.labelRes),
                                    icon = OwnTVIcon.SPORT,
                                    checked = league.id in active,
                                    onClick = { onToggleLeague(league.id) },
                                )
                            }
                        }
                        1 -> {
                            StageMenuItem(
                                text = stringResource(R.string.settings_quick_live_preview),
                                icon = OwnTVIcon.LIVE_TV,
                                value = stringResource(if (previewOn) R.string.common_on else R.string.common_off),
                                onClick = onTogglePreview,
                            )
                            // A preferred playlist tops the classic list; everything else stays
                            // listed below. Left/Right cycle, like the country switcher.
                            if (playlists.isNotEmpty()) {
                                val locale = androidx.compose.ui.text.intl.Locale.current.platformLocale
                                StageGroupLabel(stringResource(R.string.sport_prefer_playlist_title).uppercase(locale))
                                val playlistIndex = playlists.indexOfFirst { it.id == preferredPlaylistId }
                                    .let { if (it >= 0) it + 1 else 0 }
                                val playlistCount = playlists.size + 1
                                fun pickPlaylist(index: Int) {
                                    onPreferPlaylist(if (index == 0) 0L else playlists[index - 1].id)
                                }
                                StageMenuItem(
                                    text = playlists.getOrNull(playlistIndex - 1)?.name
                                        ?: stringResource(R.string.sport_prefer_none),
                                    icon = OwnTVIcon.PLAYLIST,
                                    value = stringResource(R.string.sport_region_cycler_value, (playlistIndex + 1).toString()),
                                    onClick = { pickPlaylist((playlistIndex + 1) % playlistCount) },
                                    modifier = Modifier.onPreviewKeyEvent { event ->
                                        if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                                        when (event.key) {
                                            Key.DirectionLeft -> {
                                                pickPlaylist((playlistIndex + playlistCount - 1) % playlistCount)
                                                true
                                            }
                                            Key.DirectionRight -> {
                                                pickPlaylist((playlistIndex + 1) % playlistCount)
                                                true
                                            }
                                            else -> false
                                        }
                                    },
                                )
                                Text(
                                    stringResource(R.string.sport_prefer_playlist_hint),
                                    style = stageText(15, 500),
                                    color = StageColors.Dim,
                                    maxLines = 2, overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.padding(start = 18.mpx, end = 18.mpx, top = 4.mpx),
                                )
                            }
                            // Channels per game as a slider: Left/Right adjust within
                            // SportsPrefs.Min/MaxChannelLimit, OK steps up; the value stays visible.
                            // (Replaces the former Fewer/More stepper rows; both callbacks are kept.)
                            StageMenuItem(
                                text = stringResource(R.string.sport_channel_limit_title),
                                value = channelLimit.toString(),
                                onClick = { if (channelLimit < SportsPrefs.MaxChannelLimit) onIncreaseLimit() },
                                modifier = Modifier.onPreviewKeyEvent { event ->
                                    if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                                    when (event.key) {
                                        Key.DirectionLeft -> {
                                            if (channelLimit > SportsPrefs.MinChannelLimit) onDecreaseLimit()
                                            true
                                        }
                                        Key.DirectionRight -> {
                                            if (channelLimit < SportsPrefs.MaxChannelLimit) onIncreaseLimit()
                                            true
                                        }
                                        else -> false
                                    }
                                },
                            )
                            val span = (SportsPrefs.MaxChannelLimit - SportsPrefs.MinChannelLimit).coerceAtLeast(1)
                            val fraction = (channelLimit - SportsPrefs.MinChannelLimit).coerceIn(0, span) / span.toFloat()
                            Box(
                                Modifier
                                    .fillMaxWidth()
                                    .padding(start = 14.mpx, end = 14.mpx, top = 8.mpx)
                                    .height(6.mpx)
                                    .background(StageColors.ControlFill, RoundedCornerShape(3.mpx)),
                            ) {
                                Box(
                                    Modifier
                                        .fillMaxHeight()
                                        .fillMaxWidth(fraction)
                                        .background(stageAccent.accent, RoundedCornerShape(3.mpx)),
                                )
                            }
                            Text(
                                stringResource(R.string.sport_channel_limit_hint),
                                style = stageText(15, 500),
                                color = StageColors.Dim,
                                maxLines = 1, overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.padding(start = 18.mpx, end = 18.mpx, top = 8.mpx),
                            )
                            Text(
                                pluralStringResource(R.plurals.sport_language_quota, SportsMatcher.MinPreferredCount, SportsMatcher.MinPreferredCount),
                                style = stageText(15, 500),
                                color = StageColors.Dim,
                                maxLines = 2, overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.padding(start = 18.mpx, end = 18.mpx, top = 4.mpx),
                            )
                        }
                        2 -> {
                            val locale = androidx.compose.ui.text.intl.Locale.current.platformLocale
                            // Preferred languages live in their own dialog (sublevel, ›); Always hide
                            // below is an inline checklist — the group labels keep the two apart.
                            StageGroupLabel(stringResource(R.string.sport_preferred_title).uppercase(locale))
                            StageMenuItem(
                                text = stringResource(R.string.sport_language),
                                icon = OwnTVIcon.LANGUAGE,
                                value = stringResource(R.string.sport_sublevel_chevron),
                                onClick = onOpenLanguage,
                            )
                            // Soft ranking preference: these channels sort first, everything else
                            // stays listed below. Left/Right cycle, like the region switcher.
                            StageGroupLabel(stringResource(R.string.sport_prefer_title).uppercase(locale))
                            val countryIndex = CountryPreferenceOptions
                                .indexOfFirst { it.code == preferCountry }
                                .coerceAtLeast(0)
                            val countryChoice = CountryPreferenceOptions[countryIndex]
                            StageMenuItem(
                                text = stringResource(countryChoice.labelRes),
                                icon = OwnTVIcon.NETWORK,
                                value = stringResource(R.string.sport_region_cycler_value, (countryIndex + 1).toString()),
                                onClick = {
                                    val next = (countryIndex + 1) % CountryPreferenceOptions.size
                                    onPreferCountry(CountryPreferenceOptions[next].code)
                                },
                                modifier = Modifier.onPreviewKeyEvent { event ->
                                    if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                                    when (event.key) {
                                        Key.DirectionLeft -> {
                                            val prev = (countryIndex + CountryPreferenceOptions.size - 1) % CountryPreferenceOptions.size
                                            onPreferCountry(CountryPreferenceOptions[prev].code)
                                            true
                                        }
                                        Key.DirectionRight -> {
                                            val next = (countryIndex + 1) % CountryPreferenceOptions.size
                                            onPreferCountry(CountryPreferenceOptions[next].code)
                                            true
                                        }
                                        else -> false
                                    }
                                },
                            )
                            Text(
                                stringResource(R.string.sport_prefer_hint),
                                style = stageText(15, 500),
                                color = StageColors.Dim,
                                maxLines = 2, overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.padding(start = 18.mpx, end = 18.mpx, top = 4.mpx),
                            )
                            // Always hide: excluded languages never appear, not even as exact hits.
                            StageGroupLabel(stringResource(R.string.sport_exclude_title).uppercase(locale))
                            Text(
                                stringResource(R.string.sport_exclude_subtitle),
                                style = stageText(15, 500),
                                color = StageColors.Dim,
                                maxLines = 2, overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.padding(start = 18.mpx, end = 18.mpx, bottom = 4.mpx),
                            )
                            SportLanguages.order.forEach { language ->
                                StageMenuItem(
                                    text = stringResource(language.labelRes),
                                    icon = OwnTVIcon.LANGUAGE,
                                    checked = language in excluded,
                                    onClick = { onToggleExclude(language) },
                                )
                            }
                        }
                        else -> {
                            val locale = androidx.compose.ui.text.intl.Locale.current.platformLocale
                            StageGroupLabel(stringResource(R.string.sport_ai_provider_deepseek).uppercase(locale))
                            StageMenuItem(
                                text = stringResource(R.string.sport_ai_api_key),
                                icon = OwnTVIcon.SPARKLE,
                                value = stringResource(
                                    if (deepSeekApiKey.isBlank()) R.string.sport_ai_api_key_missing
                                    else R.string.sport_ai_api_key_set,
                                ),
                                onClick = {
                                    apiKeyDraft = deepSeekApiKey
                                    showApiKey = true
                                },
                            )
                            Text(
                                stringResource(R.string.sport_ai_api_hint),
                                style = stageText(15, 500),
                                color = StageColors.Dim,
                                maxLines = 2, overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.padding(start = 18.mpx, end = 18.mpx, top = 4.mpx),
                            )
                            // The key can be stored while the AI is off — it is simply not used then.
                            if (!aiEnabled) {
                                Text(
                                    stringResource(R.string.sport_ai_functions_off_hint),
                                    style = stageText(15, 500),
                                    color = StageColors.Dim,
                                    maxLines = 2, overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.padding(start = 18.mpx, end = 18.mpx, top = 4.mpx),
                                )
                            }
                            StageGroupLabel(stringResource(R.string.sport_ai_group_results).uppercase(locale))
                            StageMenuItem(
                                text = stringResource(R.string.sport_ai_limit_title),
                                value = aiSuggestionLimit.toString(),
                                onClick = {
                                    if (aiSuggestionLimit < SportsPrefs.MaxChannelLimit) onIncreaseAiLimit()
                                },
                                modifier = Modifier.onPreviewKeyEvent { event ->
                                    if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                                    when (event.key) {
                                        Key.DirectionLeft -> {
                                            if (aiSuggestionLimit > SportsPrefs.MinChannelLimit) onDecreaseAiLimit()
                                            true
                                        }
                                        Key.DirectionRight -> {
                                            if (aiSuggestionLimit < SportsPrefs.MaxChannelLimit) onIncreaseAiLimit()
                                            true
                                        }
                                        else -> false
                                    }
                                },
                            )
                            val span = (SportsPrefs.MaxChannelLimit - SportsPrefs.MinChannelLimit).coerceAtLeast(1)
                            val fraction = (aiSuggestionLimit - SportsPrefs.MinChannelLimit).coerceIn(0, span) / span.toFloat()
                            Box(
                                Modifier
                                    .fillMaxWidth()
                                    .padding(start = 14.mpx, end = 14.mpx, top = 8.mpx)
                                    .height(6.mpx)
                                    .background(StageColors.ControlFill, RoundedCornerShape(3.mpx)),
                            ) {
                                Box(
                                    Modifier
                                        .fillMaxHeight()
                                        .fillMaxWidth(fraction)
                                        .background(stageAccent.accent, RoundedCornerShape(3.mpx)),
                                )
                            }
                            Text(
                                stringResource(R.string.sport_ai_limit_hint),
                                style = stageText(15, 500),
                                color = StageColors.Dim,
                                maxLines = 1, overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.padding(start = 18.mpx, end = 18.mpx, top = 8.mpx),
                            )
                        }
                    }
                    // The one door to the app's own settings: playlists, profile, EPG, updates. The nav
                    // rail used to carry it, and this dialog is the only settings surface left.
                    StageMenuItem(
                        text = stringResource(R.string.sport_settings_app),
                        icon = OwnTVIcon.SETTINGS,
                        onClick = onOpenAppSettings,
                    )
                }
            }
        }
    }
    if (showApiKey) {
        DeepSeekKeyDialog(
            value = apiKeyDraft,
            onValueChange = { apiKeyDraft = it },
            onConfirm = {
                onSetDeepSeekApiKey(apiKeyDraft)
                showApiKey = false
            },
            onFromPhone = {
                showApiKey = false
                showApiKeyPhone = true
            },
            onDismiss = { showApiKey = false },
        )
    }
    if (showApiKeyPhone) {
        ApiKeyPhoneDialog(
            state = remoteState,
            onStart = onStartRemoteApiKey,
            onStop = onStopRemoteApiKey,
            onDismiss = {
                showApiKeyPhone = false
                reopenKeyDialog = true
            },
        )
    }
}

/**
 * The DeepSeek key editor: a masked field, Cancel/Save, and the way out of typing a long key on a
 * remote — "Add on phone" opens [ApiKeyPhoneDialog], whose QR serves the one-field companion page
 * (`CompanionMode.AI_KEY`). The phone only fills; the key lands in this field and Save stores it.
 */
@Composable
private fun DeepSeekKeyDialog(
    value: String,
    onValueChange: (String) -> Unit,
    onConfirm: () -> Unit,
    onFromPhone: () -> Unit,
    onDismiss: () -> Unit,
) {
    val fieldFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        delay(80)
        runCatching { fieldFocus.requestFocus() }
    }
    StagePopup(
        onDismiss = onDismiss,
        title = stringResource(R.string.sport_ai_api_key),
        body = stringResource(R.string.sport_ai_key_hint),
        width = 820.mpx,
        buttons = {
            StageButton(stringResource(R.string.common_cancel), onClick = onDismiss, height = 56.mpx, textSize = 19)
            StageButton(stringResource(R.string.common_save), onClick = onConfirm, height = 56.mpx, textSize = 19, tinted = true)
        },
    ) {
        OwnTVTextField(
            value = value,
            onValueChange = onValueChange,
            label = stringResource(R.string.sport_ai_api_key),
            modifier = Modifier.fillMaxWidth(),
            focusRequester = fieldFocus,
            surface = GlassSurface.DIALOGS,
            isPassword = true,
        )
        StagePopupOption(
            title = stringResource(R.string.sport_ai_key_on_phone),
            subtitle = stringResource(R.string.sport_ai_key_on_phone_sub),
            onClick = onFromPhone,
            leading = { StagePopupIcon(OwnTVIcon.PHONE) },
            modifier = Modifier.padding(top = 14.mpx),
        )
    }
}

/**
 * "Add the key on your phone": the companion server in AI-key mode, so the QR, the PIN and the
 * address read exactly like every other "send it from another device" panel on the TV. Stops the
 * server when it leaves the composition; a failed/locked session can be restarted with a fresh PIN.
 */
@Composable
private fun ApiKeyPhoneDialog(
    state: CompanionServerState,
    onStart: () -> Unit,
    onStop: () -> Unit,
    onDismiss: () -> Unit,
) {
    BackHandler { onDismiss() }
    LaunchedEffect(Unit) { onStart() }
    DisposableEffect(Unit) { onDispose { onStop() } }
    OwnTVPopup(onDismissRequest = onDismiss, stageLayout = true) {
        Box(Modifier.fillMaxSize().background(StageColors.Scrim)) {
            Column(
                Modifier
                    .padding(start = 560.mpx, top = 150.mpx, bottom = 24.mpx)
                    .width(720.mpx)
                    .trapAllFocusExit()
                    .focusGroup(),
                verticalArrangement = Arrangement.spacedBy(20.mpx),
            ) {
                Text(
                    stringResource(R.string.sport_ai_key_on_phone),
                    style = stageText(30, 700), color = StageColors.Text, maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
                Text(
                    stringResource(R.string.sport_ai_key_on_phone_body),
                    style = stageText(17, 500), color = StageColors.Dim, maxLines = 2, overflow = TextOverflow.Ellipsis,
                )
                StageCompanionStatus(state)
                if (state is CompanionServerState.Failed || state == CompanionServerState.Locked) {
                    StageButton(
                        stringResource(R.string.setup_try_again),
                        onClick = { onStop(); onStart() },
                        height = 56.mpx,
                        textSize = 19,
                    )
                }
            }
        }
    }
}

/**
 * The AI half of the Channels section: country picker entry, loading/error states and a vertical
 * suggestion list in exactly the same row shape as the classic matcher list. Keeping it in the
 * channels section (not in a popup or a horizontal rail) is what makes it read like a normal list
 * and preserves the answer when the user returns from playback.
 */
@Composable
private fun AiChannelsPanel(
    state: SportsViewModel.AiState,
    selectedCountries: Set<AiCountry>,
    hasAiKey: Boolean,
    onOpenCountries: () -> Unit,
    onCancel: () -> Unit,
    onRetry: () -> Unit,
    onOpenSettings: () -> Unit,
    onPlay: (AiSuggestion, List<AiSuggestion>) -> Unit,
    onFocusSuggestion: (AiSuggestion) -> Unit,
) {
    val accent = stageAccent
    val loading = state is SportsViewModel.AiState.Loading
    val content = state as? SportsViewModel.AiState.Content
    val orderedCountries = selectedCountries.sortedBy { AiCountry.entries.indexOf(it) }
    val countryText = orderedCountries.joinToString { it.displayName() }
    val firstSuggestionFocus = remember { FocusRequester() }
    var wasLoading by remember { mutableStateOf(false) }
    // A completed search lands on the first suggestion, so Left/Right works immediately. A cached
    // answer restored with the screen does not steal focus — only a real Loading -> Content change.
    LaunchedEffect(state) {
        when {
            state is SportsViewModel.AiState.Loading -> wasLoading = true
            state is SportsViewModel.AiState.Content && wasLoading -> {
                wasLoading = false
                if (state.suggestions.isNotEmpty()) {
                    delay(150)
                    runCatching { firstSuggestionFocus.requestFocus() }
                }
            }
            state is SportsViewModel.AiState.Error || state is SportsViewModel.AiState.Idle -> {
                wasLoading = false
            }
        }
    }
    Column(verticalArrangement = Arrangement.spacedBy(8.mpx)) {
        StageSurface(
            onClick = {
                if (!hasAiKey) onOpenSettings() else if (!loading) onOpenCountries()
            },
            radius = 14.mpx,
            modifier = Modifier.fillMaxWidth(),
            enabled = !loading,
            idle = Modifier.background(accent.accent.copy(alpha = 0.10f), RoundedCornerShape(14.mpx)),
        ) { focused ->
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 14.mpx, vertical = 11.mpx),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.mpx),
            ) {
                OwnTVIcon(
                    OwnTVIcon.SPARKLE,
                    if (focused) accent.onAccent else accent.accent,
                    Modifier.size(22.mpx),
                )
                Column(Modifier.weight(1f)) {
                    Text(
                        stringResource(
                            if (content != null) R.string.sport_ai_search_again
                            else R.string.sport_ai_button,
                        ),
                        style = stageText(19, 700),
                        color = if (focused) accent.onAccent else StageColors.Text,
                        maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        if (!hasAiKey) {
                            stringResource(R.string.sport_ai_key_missing_title)
                        } else {
                            countryText.ifBlank { stringResource(R.string.sport_ai_country_missing) }
                        },
                        style = stageText(14, 500),
                        color = if (focused) accent.onAccent.copy(alpha = 0.8f) else StageColors.Dim,
                        maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                }
                OwnTVIcon(
                    OwnTVIcon.CHEVRON,
                    if (focused) accent.onAccent else StageColors.Dim,
                    Modifier.size(18.mpx),
                )
            }
        }
        when (state) {
            SportsViewModel.AiState.Idle -> Unit
            SportsViewModel.AiState.Loading -> {
                Row(
                    Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.mpx),
                ) {
                    Text(
                        stringResource(R.string.sport_ai_loading),
                        style = stageText(16, 500),
                        color = StageColors.Dim,
                        maxLines = 2, overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    StagePill(stringResource(R.string.common_cancel), onCancel)
                }
            }
            is SportsViewModel.AiState.Error -> {
                Column(verticalArrangement = Arrangement.spacedBy(8.mpx)) {
                    Text(
                        aiFailureText(state.failure),
                        style = stageText(16, 500),
                        color = StageColors.Dim,
                        maxLines = 2, overflow = TextOverflow.Ellipsis,
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.mpx)) {
                        if (state.failure == AiFailure.NO_KEY) {
                            StagePill(stringResource(R.string.sport_ai_open_settings), onOpenSettings, tinted = true)
                        } else {
                            StagePill(stringResource(R.string.common_retry), onRetry, tinted = true)
                            StagePill(stringResource(R.string.sport_ai_change_countries), onOpenCountries)
                        }
                    }
                }
            }
            is SportsViewModel.AiState.Content -> {
                Text(
                    pluralStringResource(
                        R.plurals.sport_ai_results_count,
                        state.suggestions.size,
                        state.suggestions.size,
                    ),
                    style = stageText(16, 600),
                    color = StageColors.Dim,
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
                if (state.suggestions.isEmpty()) {
                    Text(
                        stringResource(R.string.sport_ai_empty),
                        style = stageText(16, 500),
                        color = StageColors.Dim,
                        maxLines = 2, overflow = TextOverflow.Ellipsis,
                    )
                } else {
                    val locale = androidx.compose.ui.text.intl.Locale.current.platformLocale
                    val firstSuggestionId = state.suggestions.first().channelId
                    state.suggestions
                        .groupBy { it.country }
                        .entries
                        .sortedBy { AiCountry.entries.indexOf(it.key) }
                        .forEach { (country, countrySuggestions) ->
                            StageGroupLabel(country.displayName().uppercase(locale))
                            countrySuggestions.forEach { suggestion ->
                                AiChannelRow(
                                    suggestion = suggestion,
                                    onClick = { onPlay(suggestion, state.suggestions) },
                                    onFocus = { onFocusSuggestion(suggestion) },
                                    modifier = if (suggestion.channelId == firstSuggestionId) {
                                        Modifier.focusRequester(firstSuggestionFocus)
                                    } else {
                                        Modifier
                                    },
                                )
                            }
                        }
                }
            }
        }
    }
}

@Composable
private fun AiChannelRow(
    suggestion: AiSuggestion,
    onClick: () -> Unit,
    onFocus: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val accent = stageAccent
    StageSurface(
        onClick = onClick,
        radius = 14.mpx,
        modifier = modifier
            .fillMaxWidth()
            .onFocusChanged { if (it.isFocused) onFocus() },
    ) { focused ->
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 14.mpx, vertical = 10.mpx),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.mpx),
        ) {
            OwnTVIcon(
                OwnTVIcon.PLAY,
                if (focused) accent.onAccent else accent.accent,
                Modifier.size(22.mpx),
            )
            Column(Modifier.weight(1f)) {
                Text(
                    suggestion.channelName,
                    style = stageText(19, 600),
                    color = if (focused) accent.onAccent else StageColors.Text,
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
                Text(
                    suggestion.broadcaster,
                    style = stageText(14, 500),
                    color = if (focused) accent.onAccent.copy(alpha = 0.8f) else StageColors.Dim,
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
            }
            suggestion.qualityLabel?.let { quality ->
                Text(
                    quality,
                    style = stageText(15, 600),
                    color = if (focused) accent.onAccent else StageColors.Dim,
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
private fun StagePill(
    text: String,
    onClick: () -> Unit,
    tinted: Boolean = false,
) {
    val accent = stageAccent
    StageSurface(
        onClick = onClick,
        radius = 12.mpx,
        idle = if (tinted) {
            Modifier.background(accent.accent.copy(alpha = 0.2f), RoundedCornerShape(12.mpx))
        } else {
            Modifier
        },
    ) { focused ->
        Text(
            text,
            style = stageText(15, 600),
            color = when {
                focused -> accent.onAccent
                tinted -> accent.accent
                else -> StageColors.Text
            },
            maxLines = 1, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(horizontal = 12.mpx, vertical = 7.mpx),
        )
    }
}

@Composable
private fun AiCountryPicker(
    selected: Set<AiCountry>,
    onConfirm: (Set<AiCountry>) -> Unit,
    onDismiss: () -> Unit,
) {
    var selectedCountries by remember {
        mutableStateOf(selected.ifEmpty { setOf(AiCountry.GERMANY) })
    }
    val firstFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        delay(80)
        runCatching { firstFocus.requestFocus() }
    }
    val locale = androidx.compose.ui.text.intl.Locale.current.platformLocale
    StagePopup(
        onDismiss = onDismiss,
        title = stringResource(R.string.sport_ai_button),
        body = stringResource(R.string.sport_ai_country_subtitle),
        width = 1000.mpx,
        scroll = false,
    ) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(18.mpx),
        ) {
            // Two-column grid: 13 short rows instead of the old 26-row scroll.
            Column(Modifier.weight(1f)) {
                StageGroupLabel(stringResource(R.string.sport_ai_country_title).uppercase(locale))
                AiCountry.entries.chunked(2).forEachIndexed { rowIndex, row ->
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.mpx),
                    ) {
                        row.forEachIndexed { columnIndex, country ->
                            StageMenuItem(
                                text = country.displayName(),
                                icon = OwnTVIcon.NETWORK,
                                checked = country in selectedCountries,
                                onClick = {
                                    selectedCountries = toggleCountrySelection(selectedCountries, country)
                                },
                                modifier = Modifier.weight(1f).then(
                                    if (rowIndex == 0 && columnIndex == 0) {
                                        Modifier.focusRequester(firstFocus)
                                    } else {
                                        Modifier
                                    },
                                ),
                            )
                        }
                        if (row.size == 1) Spacer(Modifier.weight(1f))
                    }
                }
            }
            // The action is always beside the selection, never below a long scroll.
            Column(
                Modifier.width(220.mpx),
                verticalArrangement = Arrangement.spacedBy(10.mpx),
            ) {
                Text(
                    pluralStringResource(
                        R.plurals.sport_ai_selected_countries,
                        selectedCountries.size,
                        selectedCountries.size,
                    ),
                    style = stageText(15, 600),
                    color = StageColors.Dim,
                    maxLines = 2, overflow = TextOverflow.Ellipsis,
                )
                StageButton(
                    text = stringResource(R.string.sport_ai_search),
                    onClick = { if (selectedCountries.isNotEmpty()) onConfirm(selectedCountries) },
                    height = 56.mpx,
                    textSize = 19,
                    tinted = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                StageButton(
                    text = stringResource(R.string.common_cancel),
                    onClick = onDismiss,
                    height = 56.mpx,
                    textSize = 19,
                    modifier = Modifier.fillMaxWidth(),
                )
                if (selectedCountries.isEmpty()) {
                    Text(
                        stringResource(R.string.sport_ai_country_missing),
                        style = stageText(14, 500),
                        color = StageColors.Dim,
                        maxLines = 2, overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

private fun toggleCountrySelection(selected: Set<AiCountry>, country: AiCountry): Set<AiCountry> =
    if (country in selected) selected - country else selected + country

@Composable
private fun aiFailureText(failure: AiFailure): String = stringResource(
    when (failure) {
        AiFailure.NO_KEY -> R.string.sport_ai_error_no_key
        AiFailure.INVALID_KEY -> R.string.sport_ai_error_invalid_key
        AiFailure.INSUFFICIENT_CREDIT -> R.string.sport_ai_error_credit
        AiFailure.RATE_LIMITED -> R.string.sport_ai_error_rate
        AiFailure.NETWORK -> R.string.sport_ai_error_network
        AiFailure.EMPTY -> R.string.sport_ai_error_empty
        AiFailure.DISABLED -> R.string.sport_ai_error_disabled
        AiFailure.UNKNOWN -> R.string.sport_ai_error_unknown
    },
)

/**
 * Language selection for the matched channels: pick one or more languages (or "all"). Reached from
 * the Sport settings and shown automatically the first time channels are matched. The choice filters
 * the channel list by the country tag in a channel's name — see [SportLanguages].
 */
@Composable
private fun SportLanguageDialog(
    selected: Set<SportLanguage>,
    onChange: (Set<SportLanguage>) -> Unit,
    onDismiss: () -> Unit,
) {
    val firstFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        delay(60)
        runCatching { firstFocus.requestFocus() }
    }
    BackHandler { onDismiss() }
    OwnTVPopup(onDismissRequest = onDismiss, stageLayout = true) {
        Box(Modifier.fillMaxSize().background(StageColors.Scrim)) {
            Column(
                Modifier
                    .padding(start = 560.mpx, top = 150.mpx, bottom = 24.mpx)
                    .width(640.mpx)
                    .trapAllFocusExit()
                    .focusGroup(),
            ) {
                StageMenu(Modifier.verticalScroll(rememberScrollState())) {
                    StageMenuHeader(
                        title = stringResource(R.string.sport_language),
                        subtitle = stringResource(R.string.sport_language_subtitle),
                    )
                    StageMenuItem(
                        text = stringResource(R.string.sport_language_all),
                        icon = OwnTVIcon.LANGUAGE,
                        checked = selected.isEmpty(),
                        onClick = { onChange(emptySet()) },
                        modifier = Modifier.focusRequester(firstFocus),
                    )
                    SportLanguages.order.forEach { language ->
                        StageMenuItem(
                            text = stringResource(language.labelRes),
                            icon = OwnTVIcon.LANGUAGE,
                            checked = language in selected,
                            onClick = { onChange(toggleLanguage(selected, language)) },
                        )
                    }
                }
            }
        }
    }
}

/** Multi-select: from "all" the first pick narrows to that one; dropping the last one means all. */
private fun toggleLanguage(selected: Set<SportLanguage>, language: SportLanguage): Set<SportLanguage> = when {
    selected.isEmpty() -> setOf(language)
    language in selected -> selected - language
    else -> selected + language
}

/**
 * "Manage on phone": shows the companion QR + PIN while the pick list is served. Closes itself once
 * the selection was applied (the server stops a moment later) or on Back.
 */
@Composable
private fun SportsPhoneDialog(
    state: CompanionServerState,
    onDismiss: () -> Unit,
) {
    BackHandler { onDismiss() }
    var sawListening by remember { mutableStateOf(false) }
    LaunchedEffect(state) {
        when (state) {
            is CompanionServerState.Listening -> sawListening = true
            CompanionServerState.Idle -> if (sawListening) onDismiss()
            else -> Unit
        }
    }
    OwnTVPopup(onDismissRequest = onDismiss, stageLayout = true) {
        Box(Modifier.fillMaxSize().background(StageColors.Scrim)) {
            Column(
                Modifier
                    .padding(start = 560.mpx, top = 150.mpx, bottom = 24.mpx)
                    .width(720.mpx)
                    .trapAllFocusExit()
                    .focusGroup(),
                verticalArrangement = Arrangement.spacedBy(20.mpx),
            ) {
                Text(
                    stringResource(R.string.sport_manage_on_phone),
                    style = stageText(30, 700), color = StageColors.Text, maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
                StageCompanionStatus(state)
            }
        }
    }
}

@Composable
private fun TeamScoreRow(team: SportTeam, state: FixtureState) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.mpx), modifier = Modifier.fillMaxWidth()) {
        Box(Modifier.width(5.mpx).height(40.mpx).background(teamColor(team.colorHex) ?: Color.Transparent, RoundedCornerShape(3.mpx)))
        TeamLogo(team.logoUrl, 44.mpx)
        Text(team.name, style = stageText(22, 700), color = StageColors.Text, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
        team.score?.let { score ->
            Text(score, style = stageText(30, 800), color = if (state == FixtureState.LIVE) stageAccent.accent else StageColors.Text, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
private fun FollowButton(leagueId: String, abbrev: String, followed: Boolean, modifier: Modifier = Modifier, onToggle: () -> Unit) {
    val accent = stageAccent
    StageSurface(
        onClick = onToggle,
        radius = 14.mpx,
        focusStyle = StageFocus.FX,
        modifier = modifier,
        idle = if (followed) Modifier.background(accent.accent.copy(alpha = 0.14f), RoundedCornerShape(14.mpx)) else Modifier,
    ) { focused ->
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 12.mpx, vertical = 9.mpx),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.mpx),
        ) {
            OwnTVIcon(OwnTVIcon.STAR, if (focused) accent.onAccent else if (followed) accent.accent else StageColors.Dim, Modifier.size(20.mpx))
            Column(Modifier.weight(1f)) {
                Text(abbrev, style = stageText(17, 700), color = if (focused) accent.onAccent else StageColors.Text, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    stringResource(if (followed) R.string.sport_unfollow else R.string.sport_follow),
                    style = stageText(13, 500),
                    color = if (focused) accent.onAccent.copy(alpha = 0.8f) else StageColors.Dim,
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

private const val DayPattern = "EEE d.M."
private const val ColorPrefix = "FF"
private const val HashPrefix = "#"

/** Fourth tab in [SportsSettingsDialog]: the DeepSeek key and the AI suggestion limit. */
private const val AiSettingsTab = 3

/** Top-to-bottom order of the picker's region groups. */
private val RegionOrder = listOf(
    LeagueRegion.GERMANY,
    LeagueRegion.EUROPE,
    LeagueRegion.INTERNATIONAL,
    LeagueRegion.AMERICAS,
    LeagueRegion.REST,
)

private val LeagueRegion.labelRes: Int
    get() = when (this) {
        LeagueRegion.GERMANY -> R.string.sport_region_germany
        LeagueRegion.EUROPE -> R.string.sport_region_europe
        LeagueRegion.INTERNATIONAL -> R.string.sport_region_international
        LeagueRegion.AMERICAS -> R.string.sport_region_americas
        LeagueRegion.REST -> R.string.sport_region_rest
    }
