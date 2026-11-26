@file:JvmName("MainScreen")

package io.github.local.esimh4probe

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility

import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.collectIsDraggedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.foundation.text.input.setTextAndPlaceCursorAtEnd
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.local.esimh4ui.BarItem
import io.github.local.esimh4ui.LanguageSetting
import io.github.local.esimh4ui.MiuixBottomBar
import io.github.local.esimh4ui.MiuixCard
import io.github.local.esimh4ui.MiuixPage
import io.github.local.esimh4ui.MiuixRowIcon
import io.github.local.esimh4ui.MiuixStatusCard
import io.github.local.esimh4ui.PageBackground
import io.github.local.esimh4ui.StatusMode
import io.github.local.esimh4ui.SettingsNavigation
import io.github.local.esimh4ui.SettingsRoute
import io.github.local.esimh4ui.rememberSettingsBackStack
import io.github.local.esimh4ui.installerx.navigation.LocalNavigator
import top.yukonga.miuix.kmp.nav.core.NavBackStack
import io.github.local.esimh4ui.ThemeConfig
import io.github.local.esimh4ui.UiKit
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import io.github.local.esimh4ui.AppIcons
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.InfiniteProgressIndicator
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.blur.LayerBackdrop
import top.yukonga.miuix.kmp.blur.layerBackdrop
import top.yukonga.miuix.kmp.blur.rememberLayerBackdrop
import io.github.local.esimh4ui.VersionedDropdownPreference
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.preference.SwitchPreference
import top.yukonga.miuix.kmp.theme.ColorSchemeMode
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.theme.ThemeController
import io.github.local.esimh4ui.R as UiR

/**
 * The main screen in the Miuix style, written with the Miuix Compose components: home, the
 * optional Test tab and settings in a pager, with the Miuix bottom bar and, on each page, a
 * large-title top bar and Miuix's pull to refresh. [MainActivity] calls this and keeps the logic;
 * the Material 3 style uses its View pages. Dialogs (diagnostics, root) are the shared
 * View dialogs of [UiKit], which follow the Miuix style too.
 */
fun setMainContent(activity: MainActivity, state: MainUiState) {
    activity.setContent {
        val controller = remember {
            ThemeController(if (ThemeConfig.dynamicColor) ColorSchemeMode.MonetSystem else ColorSchemeMode.System)
        }
        io.github.local.esimh4ui.AppMiuixTheme(controller = controller) {
            MainContent(activity, state)
        }
    }
}

private enum class Tab(@StringRes val label: Int, @DrawableRes val icon: Int, val pageId: Int) {
    HOME(R.string.tab_home, R.drawable.ic_home_24, MainActivity.PAGE_HOME),
    TESTER(R.string.tab_tester, R.drawable.ic_science_24, MainActivity.PAGE_TESTER),
    SETTINGS(R.string.tab_settings, R.drawable.ic_settings_24, MainActivity.PAGE_SETTINGS),
}

@Composable
private fun MainContent(activity: MainActivity, state: MainUiState) {
    val colors = MiuixTheme.colorScheme
    val scope = rememberCoroutineScope()
    val tabs = if (state.showTester) listOf(Tab.HOME, Tab.TESTER, Tab.SETTINGS) else listOf(Tab.HOME, Tab.SETTINGS)
    // Replace the pager together with its tab list so stale indices and keyed-page
    // measurements cannot survive a change in page count. Preserve the logical page.
    val pagerState = androidx.compose.runtime.key(tabs) {
        val initialIndex = tabs.indexOfFirst { it.pageId == state.currentPage }
            .takeIf { it >= 0 } ?: tabs.indexOf(Tab.SETTINGS)
        rememberPagerState(initialPage = initialIndex) { tabs.size }
    }
    val current = tabs.getOrElse(pagerState.currentPage) { Tab.HOME }
    SideEffect { state.currentPage = current.pageId }

    val haptic = LocalHapticFeedback.current
    val isDragged by pagerState.interactionSource.collectIsDraggedAsState()
    LaunchedEffect(pagerState, tabs, haptic) {
        var swipeStartPage: Int? = null
        snapshotFlow { Triple(isDragged, pagerState.isScrollInProgress, pagerState.settledPage) }
            .collect { (dragging, scrolling, settledPage) ->
                if (dragging && swipeStartPage == null) {
                    swipeStartPage = settledPage
                }
                // Wait for the fling to settle; canceled swipes and programmatic navigation stay silent.
                if (!dragging && !scrolling) {
                    if (swipeStartPage != null && swipeStartPage != settledPage) {
                        haptic.performHapticFeedback(HapticFeedbackType.ContextClick)
                    }
                    swipeStartPage = null
                }
            }
    }

    val goTo: (Tab) -> Unit = { tab ->
        val index = tabs.indexOf(tab)
        if (index >= 0) scope.launch { pagerState.animateScrollToPage(index) }
    }
    val settingsStack = rememberSettingsBackStack()
    val settingsDetail = settingsStack.size > 1
    // Nested destinations own back until their stack returns to the settings index.
    BackHandler(enabled = current != Tab.HOME && !settingsDetail) { goTo(Tab.HOME) }

    // What the liquid glass bottom bar samples: the pages and the background.
    val backdrop = rememberLayerBackdrop()
    Scaffold(
        containerColor = colors.surface,
        bottomBar = {
            if (!settingsDetail) MiuixBottomBar(
                items = tabs.map { BarItem(stringResource(it.label), it.icon) },
                selected = pagerState.currentPage,
                onSelect = { index ->
                    // Both indicator drags and label taps use this callback.
                    if (index != pagerState.targetPage) {
                        haptic.performHapticFeedback(HapticFeedbackType.ContextClick)
                        scope.launch { pagerState.animateScrollToPage(index) }
                    }
                },
                backdrop = backdrop,
            )
        },
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .layerBackdrop(backdrop)
                .background(colors.surface),
        ) {
            // One background behind all pages, so it stays put while the pages are swiped.
            PageBackground()
            androidx.compose.runtime.key(tabs) {
            HorizontalPager(
                state = pagerState,
                modifier = Modifier.fillMaxSize(),
                beyondViewportPageCount = (tabs.size - 1).coerceAtMost(2),
                userScrollEnabled = !settingsDetail,
                key = { tabs[it] },
            ) { index ->
                // The pages run under the bottom bar and keep its height free at the end.
                val bottom = innerPadding.calculateBottomPadding()
                when (tabs[index]) {
                    Tab.HOME -> HomePage(activity, state, bottom, onOpenSettings = { goTo(Tab.SETTINGS) })
                    Tab.TESTER -> TesterTab(activity, state, bottom)
                    Tab.SETTINGS -> SettingsPage(activity, state, bottom, backdrop, settingsStack)
                }
            }
            }
        }
    }
}

/** The small spinner in the top bar while a probe or an action runs. */
@Composable
private fun RowScope.BusyIndicator(show: Boolean) {
    AnimatedVisibility(visible = show) {
        InfiniteProgressIndicator(
            modifier = Modifier.padding(end = 16.dp),
            color = MiuixTheme.colorScheme.primary,
        )
    }
}

// -------------------------------------------------------------------------------------------------
// Home
// -------------------------------------------------------------------------------------------------

@Composable
private fun HomePage(activity: MainActivity, state: MainUiState, bottom: Dp, onOpenSettings: () -> Unit) {
    val model = state.model
    val palette = activity.paletteColors()
    var refreshing by remember { mutableStateOf(false) }
    val refreshedText = stringResource(R.string.snack_refreshed)
    val showDiagnostics: () -> Unit = { activity.showDiagnosticsDialog() }
    MiuixPage(
        title = stringResource(R.string.app_name),
        bottomPadding = bottom,
        drawBackground = false,
        isRefreshing = refreshing,
        onRefresh = {
            refreshing = true
            activity.refreshDashboard(Runnable {
                refreshing = false
                activity.showToast(refreshedText)
            })
        },
        actions = { BusyIndicator(state.busy && !refreshing) },
    ) {
        item(key = "status") {
            AnimatedContent(targetState = model, contentKey = { it?.bannerOk }, label = "homeStatus") { model ->
            if (model == null) {
                MiuixStatusCard(palette, StatusMode.LOADING, stringResource(R.string.status_loading), onClick = { activity.onProbeStatusTap() })
            } else if (model.bannerOk) {
                MiuixStatusCard(palette, StatusMode.OK, model.bannerTitle, model.bannerText, model.bannerTags, onClick = { activity.onProbeStatusTap() })
            } else {
                MiuixStatusCard(
                    palette = palette,
                    mode = StatusMode.ERROR,
                    title = model.bannerTitle,
                    text = model.bannerText,
                    onClick = { activity.onProbeStatusTap() },
                    actionText = model.bannerActionText,
                    onAction = when (model.bannerAction) {
                        DashboardModel.BannerAction.OPEN_SETTINGS -> onOpenSettings
                        DashboardModel.BannerAction.DIAGNOSTICS -> showDiagnostics
                        else -> null
                    },
                )
            }
        }
        }
        val alert = model?.alert
        item(key = "alert") {
            AnimatedContent(targetState = alert, label = "homeAlert") { alert ->
                if (alert != null) {
                MiuixCard(insideMargin = PaddingValues(horizontal = 16.dp, vertical = 14.dp), color = Color(palette.errorContainer)) {
                    Row {
                        Icon(
                            imageVector = ImageVector.vectorResource(R.drawable.ic_error_24),
                            contentDescription = null,
                            modifier = Modifier.padding(top = 2.dp, end = 12.dp).size(20.dp),
                            tint = Color(palette.onErrorContainer),
                        )
                        Text(text = alert, color = Color(palette.onErrorContainer), fontSize = 12.8.sp)
                    }
                }
            }
        }
        }
        item(key = "healthTitle") { SmallTitle(text = stringResource(R.string.section_health)) }
        item(key = "sim") { SimCard(activity, model, palette) }
    }
}

@Composable
private fun SimCard(activity: MainActivity, model: DashboardModel?, palette: UiKit.Palette) {
    val colors = MiuixTheme.colorScheme
    val context = LocalContext.current
    MiuixCard(insideMargin = PaddingValues(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(44.dp)
                    .background(Color(palette.secondaryContainer), RoundedCornerShape(12.dp)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = ImageVector.vectorResource(R.drawable.ic_sim_card_24),
                    contentDescription = null,
                    modifier = Modifier.size(22.dp),
                    tint = Color(palette.onSecondaryContainer),
                )
            }
            Column(Modifier.weight(1f).padding(start = 14.dp, end = 8.dp)) {
                Text(
                    text = stringResource(R.string.sim2_title),
                    color = colors.onSurface,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    text = model?.sim2Meta ?: stringResource(R.string.sim2_carrier, "…"),
                    modifier = Modifier.padding(top = 2.dp),
                    color = colors.onSurfaceVariantSummary,
                    fontSize = 12.sp,
                )
            }
            val badgeFg = if (model != null) Color(context.getColor(model.badgeFgRes)) else colors.onSurfaceVariantSummary
            val badgeBg = if (model != null) Color(context.getColor(model.badgeBgRes)) else Color(palette.tileBg)
            Text(
                text = stringResource(model?.badgeRes ?: R.string.badge_checking),
                modifier = Modifier
                    .background(badgeBg, RoundedCornerShape(999.dp))
                    .padding(horizontal = 11.dp, vertical = 5.dp),
                color = badgeFg,
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
            )
        }
        Row(Modifier.padding(top = 14.dp)) {
            HealthTile(model?.tileMount, R.string.tile_mount, palette)
            Spacer(Modifier.width(8.dp))
            HealthTile(model?.tilePhoneHook, R.string.tile_phone_hook, palette)
        }
        Row(Modifier.padding(top = 8.dp)) {
            HealthTile(model?.tileLpaHook, R.string.tile_lpa_hook, palette)
            Spacer(Modifier.width(8.dp))
            HealthTile(model?.tileEuicc, R.string.tile_euicc, palette)
        }
        TextButton(
            text = stringResource(R.string.btn_reprobe),
            onClick = { activity.triggerReprobeSim2() },
            modifier = Modifier.fillMaxWidth().padding(top = 16.dp),
        )
        TextButton(
            text = stringResource(R.string.btn_manage_esim),
            onClick = { activity.openNativeEsimManager() },
            modifier = Modifier.fillMaxWidth().padding(top = 10.dp),
            enabled = model?.manageEnabled ?: false,
            colors = ButtonDefaults.textButtonColorsPrimary(),
        )
    }
}

@Composable
private fun RowScope.HealthTile(tile: DashboardModel.Tile?, @StringRes label: Int, palette: UiKit.Palette) {
    val colors = MiuixTheme.colorScheme
    val context = LocalContext.current
    Column(
        modifier = Modifier
            .weight(1f)
            .background(Color(palette.tileBg), RoundedCornerShape(12.dp))
            .padding(horizontal = 12.dp, vertical = 10.dp),
    ) {
        Text(text = stringResource(label), color = colors.onSurfaceVariantSummary, fontSize = 11.5.sp)
        Text(
            text = tile?.display() ?: ("○ " + stringResource(R.string.badge_checking)),
            modifier = Modifier.padding(top = 3.dp),
            color = if (tile != null) Color(context.getColor(tile.colorRes())) else colors.onSurface,
            fontSize = 13.sp,
            fontWeight = FontWeight.Bold,
        )
    }
}

// -------------------------------------------------------------------------------------------------
// Test tab
// -------------------------------------------------------------------------------------------------

@Composable
private fun TesterTab(activity: MainActivity, state: MainUiState, bottom: Dp) {
    val tester = activity.testerPage() ?: return
    val palette = activity.paletteColors()
    val colors = MiuixTheme.colorScheme
    // Bumped by the tester whenever its status or log changed.
    var version by remember { mutableIntStateOf(0) }
    DisposableEffect(tester) {
        tester.setListener { version++ }
        onDispose { tester.setListener(null) }
    }
    val status = remember(tester, version) { tester.status() }
    val log = remember(tester, version) { tester.log() }
    val logVisible = remember(tester, version) { tester.isLogVisible() }
    val code = rememberTextFieldState(TesterPage.DEFAULT_MOCK_CODE)

    var refreshing by remember { mutableStateOf(false) }
    LaunchedEffect(refreshing) {
        if (refreshing) {
            tester.refreshStatus()
            // The status is read at once; keep the indicator visible briefly.
            delay(400)
            refreshing = false
        }
    }

    MiuixPage(
        title = stringResource(R.string.tester_title),
        bottomPadding = bottom,
        drawBackground = false,
        isRefreshing = refreshing,
        onRefresh = { refreshing = true },
        actions = { BusyIndicator(state.busy) },
    ) {
        item(key = "status") {
            when {
                status.checking -> MiuixStatusCard(palette, StatusMode.LOADING, status.title, onClick = { tester.onStatusTap() })
                status.ready -> MiuixStatusCard(palette, StatusMode.OK, status.title, status.text, status.tags, onClick = { tester.onStatusTap() })
                else -> MiuixStatusCard(palette, StatusMode.ERROR, status.title, status.text, onClick = { tester.onStatusTap() })
            }
        }
        item(key = "mockTitle") { SmallTitle(text = stringResource(R.string.tester_mock_title)) }
        item(key = "mock") {
            MiuixCard(insideMargin = PaddingValues(16.dp)) {
                Text(
                    text = stringResource(R.string.tester_safety_note),
                    color = colors.onSurfaceVariantSummary,
                    fontSize = 12.5.sp,
                )
                TextField(
                    state = code,
                    modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
                    lineLimits = TextFieldLineLimits.SingleLine,
                    textStyle = MiuixTheme.textStyles.main.copy(fontFamily = FontFamily.Monospace, fontSize = 13.sp),
                )
                TextButton(
                    text = stringResource(R.string.tester_reset_code),
                    onClick = {
                        code.setTextAndPlaceCursorAtEnd(TesterPage.DEFAULT_MOCK_CODE)
                        tester.logCodeReset()
                    },
                    modifier = Modifier.fillMaxWidth().padding(top = 10.dp),
                )
                TextButton(
                    text = stringResource(R.string.tester_test1),
                    onClick = {
                        val raw = code.text.toString().trim()
                        if (raw.isEmpty()) code.setTextAndPlaceCursorAtEnd(TesterPage.DEFAULT_MOCK_CODE)
                        tester.startMockDownload(raw)
                    },
                    modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
                    colors = ButtonDefaults.textButtonColorsPrimary(),
                )
                TextButton(
                    text = stringResource(R.string.tester_test2),
                    onClick = { tester.startLpaUriTest(code.text.toString()) },
                    modifier = Modifier.fillMaxWidth().padding(top = 10.dp),
                )
                TextButton(
                    text = stringResource(R.string.tester_test3),
                    onClick = { tester.startStandardActivationTest() },
                    modifier = Modifier.fillMaxWidth().padding(top = 10.dp),
                )
            }
        }
        item(key = "logSection") {
            AnimatedVisibility(visible = logVisible) {
                Column {
                SmallTitle(text = stringResource(R.string.tester_console_title))
                MiuixCard(insideMargin = PaddingValues(0.dp)) {
                    SelectionContainer {
                        Text(
                            text = log,
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(min = 120.dp)
                                .background(Color(palette.tileBg), RoundedCornerShape(16.dp))
                                .padding(horizontal = 14.dp, vertical = 12.dp),
                            color = colors.onSurface,
                            fontSize = 12.sp,
                            fontFamily = FontFamily.Monospace,
                            lineHeight = 18.sp,
                        )
                    }
                }
                TextButton(
                    text = stringResource(R.string.tester_clear_log),
                    onClick = { tester.clearLog() },
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp),
                )
                TextButton(
                    text = stringResource(R.string.tester_hide_log),
                    onClick = { tester.hideLog() },
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp).padding(top = 10.dp),
                )
                }
            }
        }
    }
}

// -------------------------------------------------------------------------------------------------
// Settings
// -------------------------------------------------------------------------------------------------

@Composable
private fun SettingsPage(activity: MainActivity, state: MainUiState, bottom: Dp, backdrop: LayerBackdrop, backStack: NavBackStack) {
    var language by remember { mutableIntStateOf(LanguageSetting.current(activity)) }
    val model = state.model
    // A switch shows the new value while its change runs, and the old one again if it fails.
    val overrides = remember { mutableStateMapOf<String, Boolean>() }
    val pending = remember { mutableStateMapOf<String, Boolean>() }
    // A new report shows the real state; keep only the values of changes still running.
    LaunchedEffect(model) {
        overrides.keys.filter { it !in pending }.forEach { overrides.remove(it) }
    }

    @Composable
    fun SettingSwitch(key: String, row: DashboardModel.Row?, @StringRes title: Int, @DrawableRes icon: Int) {
        SwitchPreference(
            checked = overrides[key] ?: row?.checked ?: false,
            onCheckedChange = { on ->
                overrides[key] = on
                pending[key] = true
                activity.requestSwitch(key, on) { ok ->
                    pending.remove(key)
                    if (!ok) overrides.remove(key)
                }
            },
            title = stringResource(title),
            summary = row?.summary ?: stringResource(R.string.status_loading_summary),
            startAction = { MiuixRowIcon(icon) },
            enabled = (row?.enabled ?: false) && pending[key] != true,
        )
    }

    SettingsNavigation(backStack) { page ->
    val navigator = LocalNavigator.current
    if (page == "personalize" || page == "theme") {
        io.github.local.esimh4ui.ThemePage(
            activity = activity,
            showLanguage = true,
            dynamicColor = ThemeConfig.dynamicColor,
            onDynamicColorChange = {
                ThemeConfig.setDynamicColor(activity, it)
                activity.recreate()
            },
            navigateBack = { navigator.pop() },
        )
        return@SettingsNavigation
    }
    if (page == "about") {
        AboutPage(
            activity = activity,
            isDark = androidx.compose.foundation.isSystemInDarkTheme(),
            onBack = { navigator.pop() },
            onOpenLicenses = { navigator.push(SettingsRoute("licenses")) },
        )
        return@SettingsNavigation
    }
    if (page == "licenses" || page == "projectLicense") {
        LicensesPage(
            activity = activity,
            showProject = page == "projectLicense",
            onBack = { navigator.pop() },
            onOpenProject = { navigator.push(SettingsRoute("projectLicense")) },
        )
        return@SettingsNavigation
    }
    val title = when (page) {
        "core" -> R.string.section_core
        "system" -> R.string.section_root
        "personalize" -> R.string.section_personalize
        else -> R.string.tab_settings
    }
    MiuixPage(
        title = stringResource(title),
        bottomPadding = bottom,
        drawBackground = true,
        onBack = if (page == "root") null else ({ navigator.pop() }),
        actions = { BusyIndicator(state.busy) },
    ) {
        if (page == "root") {
            item(key = "master") {
                MiuixCard(modifier = Modifier.padding(top = 8.dp)) {
                    SettingSwitch(MainActivity.SW_MASTER, model?.master, R.string.row_master, R.drawable.ic_power_24)
                }
            }
            item(key = "categories") {
                MiuixCard {
                    listOf(
                        Triple("core", R.string.section_core, R.drawable.ic_sim_card_24),
                        Triple("system", R.string.section_root, R.drawable.ic_shield_24),
                        Triple("personalize", R.string.section_personalize, UiR.drawable.ic_palette_24),
                        Triple("status", R.string.row_device_status, R.drawable.ic_monitor_heart_24),
                    ).forEach { (route, label, icon) ->
                        ArrowPreference(
                            title = stringResource(label),
                            startAction = {
                                if (route == "core") {
                                    Icon(
                                        imageVector = AppIcons.Edashcard,
                                        contentDescription = null,
                                        modifier = Modifier.padding(end = 6.dp),
                                        tint = MiuixTheme.colorScheme.onBackground,
                                    )
                                } else {
                                    MiuixRowIcon(icon)
                                }
                            },
                            onClick = {
                                if (route == "status") activity.openDeviceStatus()
                                else navigator.push(SettingsRoute(route))
                            },
                        )
                    }
                }
            }
        }
        if (page == "core") {
        item(key = "core") {
            MiuixCard(modifier = Modifier.padding(top = 8.dp)) {
                SettingSwitch(MainActivity.SW_SYSTEM_ESIM, model?.systemEsim, R.string.row_system_esim, R.drawable.ic_sync_alt_24)
                SettingSwitch(MainActivity.SW_CARRIER_BRIDGE, model?.carrierBridge, R.string.row_carrier_bridge, R.drawable.ic_verified_user_24)
                SettingSwitch(MainActivity.SW_LPA_URI_BRIDGE, model?.lpaUriBridge, R.string.row_lpa_uri, R.drawable.ic_link_24)
                SettingSwitch(MainActivity.SW_REWRITE_TEXT, model?.rewriteText, R.string.row_rewrite_text, R.drawable.ic_translate_24)
            }
        }
        }
        if (page == "system") {
        item(key = "root") {
            MiuixCard(modifier = Modifier.padding(top = 8.dp)) {
                SwitchPreference(
                    checked = state.showTester,
                    onCheckedChange = { activity.setTesterTabShown(it) },
                    title = stringResource(R.string.row_tester),
                    summary = stringResource(if (state.showTester) R.string.tester_sub_on else R.string.tester_sub_off),
                    startAction = { MiuixRowIcon(R.drawable.ic_science_24) },
                )
                SettingSwitch(MainActivity.SW_UMOUNT_GUARD, model?.umountGuard, R.string.row_umount_guard, R.drawable.ic_shield_24)
                SettingSwitch(MainActivity.SW_ESTK_SE1, model?.estkSe1, R.string.row_estk_se1, R.drawable.ic_sync_alt_24)
            }
        }
        item(key = "updates") {
            MiuixCard {
                var nightly by remember { mutableStateOf(UpdateChecker.isNightly(activity)) }
                var startup by remember { mutableStateOf(UpdateChecker.checkAtStartup(activity)) }
                var autoInstall by remember { mutableStateOf(UpdateChecker.autoInstall(activity)) }
                var selectedManager by remember { mutableStateOf(UpdateChecker.selectedManager(activity)) }
                val managers = UpdateChecker.installedManagers(activity)
                SwitchPreference(
                    title = stringResource(R.string.update_startup),
                    checked = startup,
                    onCheckedChange = {
                        startup = it
                        UpdateChecker.setCheckAtStartup(activity, it)
                    },
                )
                VersionedDropdownPreference(
                    title = stringResource(R.string.update_channel),
                    backdrop = backdrop,
                    items = listOf("Stable", "Nightly"),
                    selectedIndex = if (nightly) 1 else 0,
                    onSelectedIndexChange = {
                        nightly = it == 1
                        UpdateChecker.setNightly(activity, nightly)
                    },
                )
                SwitchPreference(
                    title = stringResource(R.string.update_auto_install),
                    checked = autoInstall,
                    onCheckedChange = {
                        autoInstall = it
                        UpdateChecker.setAutoInstall(activity, it)
                    },
                )
                AnimatedVisibility(visible = autoInstall) {
                    if (managers.isEmpty()) {
                        ArrowPreference(
                            title = stringResource(R.string.update_manager),
                            endActions = { EndText(stringResource(R.string.update_manager_none)) },
                            onClick = {},
                        )
                    } else {
                        VersionedDropdownPreference(
                            title = stringResource(R.string.update_manager),
                            backdrop = backdrop,
                            items = listOf(stringResource(R.string.update_manager_select)) + managers.map { it.name },
                            selectedIndex = managers.indexOfFirst { it.packageName == selectedManager } + 1,
                            onSelectedIndexChange = { index ->
                                selectedManager = managers.getOrNull(index - 1)?.packageName.orEmpty()
                                UpdateChecker.setSelectedManager(activity, selectedManager)
                            },
                        )
                    }
                }
            }
        }
        }
        if (page == "root") {
        item(key = "about") {
            MiuixCard {
                ArrowPreference(
                    title = stringResource(R.string.section_about),
                    startAction = { MiuixRowIcon(R.drawable.ic_info_24) },
                    endActions = { EndText(AboutInfo.versionName(activity)) },
                    onClick = { navigator.push(SettingsRoute("about")) },
                )
            }
        }
    }
    }
    }
}

@Composable
private fun EndText(text: String) {
    Text(
        text = text,
        fontSize = MiuixTheme.textStyles.body2.fontSize,
        color = MiuixTheme.colorScheme.onSurfaceVariantActions,
    )
}
