@file:JvmName("ThemeScreen")

package io.github.local.esimh4ui

import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.BlurredEdgeTreatment
import androidx.compose.ui.draw.blur
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.CardDefaults
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.InfiniteProgressIndicator
import top.yukonga.miuix.kmp.basic.MiuixScrollBehavior
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.TopAppBar
import top.yukonga.miuix.kmp.blur.layerBackdrop
import top.yukonga.miuix.kmp.blur.rememberLayerBackdrop
import top.yukonga.miuix.kmp.glass.GlassIconButton
import top.yukonga.miuix.kmp.glass.GlassTopAppBar
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.preference.SliderPreference
import top.yukonga.miuix.kmp.preference.SwitchPreference
import top.yukonga.miuix.kmp.theme.ColorSchemeMode
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.theme.ThemeController
import top.yukonga.miuix.kmp.utils.overScrollVertical
import top.yukonga.miuix.kmp.utils.scrollEndHaptic

/**
 * Shows the Miuix theme page, written with the Miuix Compose components. [ThemeActivity] calls
 * this in the Miuix style; the Material 3 style still uses the View page.
 *
 * The page reads the settings as state, so it follows every change without being recreated.
 * Language and interface style changes restart the UI task through the launcher splash.
 * The platform recreates it when the effective dark mode changes.
 */
fun setThemeContent(activity: ThemeActivity, showLanguage: Boolean) {
    activity.setContent {
        var dynamicColor by remember { mutableStateOf(ThemeConfig.dynamicColor) }
        // The app applies its dark mode setting through the platform (UiModeManager), so the
        // "System" modes follow it.
        val controller = remember(dynamicColor) {
            ThemeController(if (dynamicColor) ColorSchemeMode.MonetSystem else ColorSchemeMode.System)
        }
        AppMiuixTheme(controller = controller) {
            ThemePage(
                activity = activity,
                showLanguage = showLanguage,
                dynamicColor = dynamicColor,
                onDynamicColorChange = {
                    ThemeConfig.setDynamicColor(activity, it)
                    dynamicColor = it
                },
            )
        }
    }
}

@Composable
fun ThemePage(
    activity: ThemedActivity,
    showLanguage: Boolean,
    dynamicColor: Boolean,
    onDynamicColorChange: (Boolean) -> Unit,
    navigateBack: () -> Unit = { activity.finish() },
) {
    val colors = MiuixTheme.colorScheme
    val scope = rememberCoroutineScope()
    var appearanceTapCount by rememberSaveable { mutableIntStateOf(0) }

    var miuixVersion by remember { mutableIntStateOf(ThemeConfig.miuixVersion) }
    var barGlass by remember { mutableStateOf(ThemeConfig.barGlass) }
    var progressiveBlur by remember { mutableStateOf(ThemeConfig.progressiveBlur) }
    var barFloating by remember { mutableStateOf(ThemeConfig.barFloating) }
    var liquidGlass by remember { mutableStateOf(ThemeConfig.liquidGlass) }
    var nightMode by remember { mutableIntStateOf(ThemeConfig.nightMode) }
    var language by remember { mutableIntStateOf(LanguageSetting.current(activity)) }
    var bgBlur by remember { mutableFloatStateOf(ThemeConfig.bgBlur.toFloat()) }
    var bgDim by remember { mutableFloatStateOf(ThemeConfig.bgDim.toFloat()) }
    var cardAlpha by remember { mutableFloatStateOf(ThemeConfig.cardAlpha.toFloat()) }
    var importing by remember { mutableStateOf(false) }
    var showTexture by remember { mutableStateOf(ThemeConfig.showTexture) }
    var predictiveBack by remember { mutableIntStateOf(ThemeConfig.predictiveBack) }
    var backExitDirection by remember { mutableIntStateOf(ThemeConfig.backExitDirection) }
    val backStyles = listOf(ThemeConfig.BACK_NONE, ThemeConfig.BACK_AOSP, ThemeConfig.BACK_MIUIX,
        ThemeConfig.BACK_CLASSIC, ThemeConfig.BACK_SCALE)

    // Bumped after the image is replaced or removed, so it is decoded again.
    var backgroundVersion by remember { mutableIntStateOf(0) }
    val background = remember(backgroundVersion) { ThemeConfig.loadBackground(activity)?.asImageBitmap() }
    val hasBackground = background != null

    val pickFailed = stringResource(R.string.theme_bg_pick_failed)
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        importing = true
        scope.launch {
            val ok = withContext(Dispatchers.IO) { ThemeConfig.importBackground(activity.applicationContext, uri) }
            importing = false
            if (ok) {
                backgroundVersion++
            } else {
                Toast.makeText(activity, pickFailed, Toast.LENGTH_SHORT).show()
            }
        }
    }
    val pickImage = {
        try {
            // The system photo picker, or the document picker where it is missing; no permission needed.
            picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
        } catch (e: Throwable) {
            Toast.makeText(activity, pickFailed, Toast.LENGTH_SHORT).show()
        }
    }

    val backdrop = rememberLayerBackdrop()
    val scrollBehavior = MiuixScrollBehavior()
    val listState = rememberLazyListState()
    val title = stringResource(R.string.personalization_title)
    val back = stringResource(R.string.ui_back)

    val cardColor = if (hasBackground) colors.surfaceContainer.copy(alpha = cardAlpha / 100f) else colors.surfaceContainer

    Scaffold(
        containerColor = colors.surface,
        topBar = {
            Crossfade(targetState = barGlass && miuixVersion == 4, animationSpec = tween(250), label = "themeTopBar") { glass ->
            if (glass) {
                // Glass bar: transparent at rest; once the page scrolls, the back button gets its
                // glass pill and the band behind the bar fades in (a progressive blur if enabled).
                GlassTopAppBar(
                    title = title,
                    backdrop = backdrop,
                    isContentScrolled = listState.canScrollBackward,
                    scrollBehavior = scrollBehavior,
                    progressiveBlur = progressiveBlur,
                    navigationIcon = {
                        GlassIconButton(onClick = navigateBack) {
                            Icon(
                                imageVector = AppIcons.ChevronBackward,
                                contentDescription = back,
                                modifier = Modifier.size(28.dp),
                                tint = colors.onSurface,
                            )
                        }
                    },
                )
            } else {
                TopAppBar(
                    title = title,
                    color = colors.surface,
                    scrollBehavior = scrollBehavior,
                    navigationIcon = {
                        IconButton(onClick = navigateBack) {
                            Icon(
                                imageVector = AppIcons.ChevronBackward,
                                contentDescription = back,
                                tint = colors.onBackground,
                            )
                        }
                    },
                )
            }
            }
        },
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .layerBackdrop(backdrop)
                .background(colors.surface),
        ) {
            if (background != null) {
                Image(
                    bitmap = background,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .fillMaxSize()
                        .then(
                            if (bgBlur >= 1f) {
                                Modifier.blur(bgBlur.roundToInt().dp, BlurredEdgeTreatment.Rectangle)
                            } else {
                                Modifier
                            },
                        ),
                )
                // Dim toward the page color, so text keeps its contrast in both themes.
                Box(
                    Modifier
                        .fillMaxSize()
                        .background(colors.surface.copy(alpha = bgDim.roundToInt().coerceIn(0, 90) / 100f)),
                )
            }

            LazyColumn(
                state = listState,
                modifier = Modifier
                    .fillMaxSize()
                    .scrollEndHaptic()
                    .overScrollVertical(onOffsetChanged = { scrollBehavior.state.overscrollOffset = it })
                    .nestedScroll(scrollBehavior.nestedScrollConnection),
                contentPadding = PaddingValues(
                    top = innerPadding.calculateTopPadding(),
                    bottom = innerPadding.calculateBottomPadding() + 12.dp,
                ),
            ) {
                item(key = "appearanceTitle") {
                    SmallTitle(
                        text = stringResource(R.string.theme_section_appearance),
                        modifier = Modifier.clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                        ) {
                            if (appearanceTapCount < 7) appearanceTapCount++
                        },
                    )
                }
                item(key = "appearance") {
                    SettingsCard(cardColor) {
                        AnimatedVisibility(
                            visible = appearanceTapCount >= 7,
                            enter = expandVertically(tween(250)) + fadeIn(tween(250)),
                            exit = shrinkVertically(tween(250)) + fadeOut(tween(200)),
                        ) {
                        VersionedDropdownPreference(
                            title = stringResource(R.string.theme_style),
                            summary = stringResource(R.string.theme_style_summary),
                            items = listOf(
                                stringResource(R.string.theme_style_miuix),
                                stringResource(R.string.theme_style_material3),
                            ),
                            selectedIndex = if (UiKit.isMiuix()) 0 else 1,
                            backdrop = backdrop,
                            startAction = { RowIcon(R.drawable.ic_style_24) },
                            onSelectedIndexChange = {
                                if (it == 0) {
                                    ThemeConfig.setStyle(activity, UiKit.Style.MIUIX)
                                } else {
                                    ThemeConfig.setStyle(activity, UiKit.Style.MATERIAL3)
                                }
                            },
                        )
                        }
                        VersionedDropdownPreference(
                            title = stringResource(R.string.theme_night),
                            items = listOf(
                                stringResource(R.string.theme_night_system),
                                stringResource(R.string.theme_night_light),
                                stringResource(R.string.theme_night_dark),
                            ),
                            selectedIndex = nightMode,
                            backdrop = backdrop,
                            startAction = { RowIcon(R.drawable.ic_dark_mode_24) },
                            onSelectedIndexChange = {
                                // The platform recreates the activity if the effective mode changes.
                                nightMode = it
                                ThemeConfig.setNightMode(activity, it)
                            },
                        )
                        if (showLanguage) {
                            VersionedDropdownPreference(
                                title = stringResource(R.string.lang_title),
                                items = LanguageSetting.labels(activity).toList(),
                                selectedIndex = language,
                                backdrop = backdrop,
                                startAction = { RowIcon(R.drawable.ic_language_24) },
                                onSelectedIndexChange = {
                                    // Replace the UI task after applying the new locale.
                                    language = it
                                    LanguageSetting.apply(activity, it)
                                },
                            )
                        }
                        VersionedDropdownPreference(
                            title = stringResource(R.string.theme_miuix_version),
                            items = listOf("OS2", "OS3", "OS4"),
                            selectedIndex = miuixVersion - 2,
                            backdrop = backdrop,
                            startAction = { RowIcon(R.drawable.ic_layers_24) },
                            onSelectedIndexChange = {
                                ThemeConfig.setMiuixVersion(activity, it + 2)
                                miuixVersion = ThemeConfig.miuixVersion
                                barGlass = ThemeConfig.barGlass
                                progressiveBlur = ThemeConfig.progressiveBlur
                                liquidGlass = ThemeConfig.liquidGlass
                                barFloating = ThemeConfig.barFloating
                                predictiveBack = ThemeConfig.predictiveBack
                            },
                        )
                        SwitchPreference(
                            title = stringResource(R.string.theme_amoled),
                            summary = stringResource(R.string.theme_amoled_summary),
                            checked = ThemeConfig.amoledBackground,
                            startAction = { RowIcon(R.drawable.ic_dark_mode_24) },
                            onCheckedChange = {
                                ThemeConfig.setAmoledBackground(activity, it)
                                activity.recreate()
                            },
                        )
                        SwitchPreference(
                            title = stringResource(R.string.theme_dynamic_color),
                            summary = stringResource(R.string.theme_dynamic_color_summary_miuix),
                            checked = dynamicColor,
                            startAction = { RowIcon(R.drawable.ic_dynamic_color_24) },
                            onCheckedChange = onDynamicColorChange,
                        )
                    }
                }
                // Top and bottom bars
                item(key = "bars") {
                    SettingsVisibility(visible = miuixVersion > 2) {
                    Column {
                    SmallTitle(text = stringResource(R.string.theme_section_bottom_bar))
                    SettingsCard(cardColor) {
                        SettingsVisibility(visible = miuixVersion == 4) {
                        SwitchPreference(
                            title = stringResource(R.string.theme_bar_glass),
                            summary = stringResource(R.string.theme_bar_glass_summary),
                            checked = barGlass,
                            startAction = { RowIcon(R.drawable.ic_bottom_bar_24) },
                            onCheckedChange = {
                                ThemeConfig.setBarGlass(activity, it)
                                barGlass = ThemeConfig.barGlass
                            },
                        )
                        }
                        SettingsVisibility(visible = barGlass && miuixVersion == 4) {
                        SwitchPreference(
                            title = stringResource(R.string.theme_progressive_blur),
                            summary = stringResource(R.string.theme_progressive_blur_summary),
                            checked = progressiveBlur,
                            startAction = { RowIcon(R.drawable.ic_blur_24) },
                            onCheckedChange = {
                                ThemeConfig.setProgressiveBlur(activity, it)
                                progressiveBlur = ThemeConfig.progressiveBlur
                            },
                        )
                        }
                        SwitchPreference(
                            title = stringResource(R.string.theme_bar_floating),
                            summary = stringResource(R.string.theme_bar_floating_summary),
                            checked = barFloating,
                            startAction = { RowIcon(R.drawable.ic_floating_bar_24) },
                            onCheckedChange = {
                                ThemeConfig.setBarFloating(activity, it)
                                barFloating = ThemeConfig.barFloating
                            },
                        )
                        SettingsVisibility(visible = miuixVersion == 4 && barFloating) {
                            SwitchPreference(
                                title = stringResource(R.string.theme_liquid_glass),
                                summary = stringResource(R.string.theme_liquid_glass_summary),
                                checked = liquidGlass,
                                startAction = { RowIcon(R.drawable.ic_liquid_glass_24) },
                                onCheckedChange = {
                                    ThemeConfig.setLiquidGlass(activity, it)
                                    liquidGlass = ThemeConfig.liquidGlass
                                },
                            )
                        }
                    }
                    }
                    }
                }

                item(key = "back") {
                    SettingsVisibility(visible = miuixVersion > 2) {
                    Column {
                    SmallTitle(text = stringResource(R.string.personalization_navigation))
                    SettingsCard(cardColor) {
                        VersionedDropdownPreference(
                            title = stringResource(R.string.theme_predictive_back),
                            items = listOf(
                                stringResource(R.string.theme_back_none),
                                stringResource(R.string.theme_back_aosp),
                                stringResource(R.string.theme_back_miuix),
                                stringResource(R.string.theme_back_classic),
                                stringResource(R.string.theme_back_scale),
                            ),
                            selectedIndex = backStyles.indexOf(predictiveBack),
                            backdrop = backdrop,
                            startAction = { RowIcon(R.drawable.ic_arrow_back_24) },
                            onSelectedIndexChange = {
                                ThemeConfig.setPredictiveBack(activity, backStyles[it])
                                predictiveBack = ThemeConfig.predictiveBack
                            },
                        )
                        SettingsVisibility(visible = predictiveBack == ThemeConfig.BACK_SCALE) {
                            VersionedDropdownPreference(
                                title = stringResource(R.string.theme_back_direction),
                                items = listOf(stringResource(R.string.theme_back_follow), stringResource(R.string.theme_back_right), stringResource(R.string.theme_back_left)),
                                selectedIndex = backExitDirection,
                                backdrop = backdrop,
                                startAction = { RowIcon(R.drawable.ic_arrow_back_24) },
                                onSelectedIndexChange = {
                                    ThemeConfig.setBackExitDirection(activity, it)
                                    backExitDirection = it
                                },
                            )
                        }
                    }
                    }
                    }
                }

                // Background
                item(key = "backgroundTitle") { SmallTitle(text = stringResource(R.string.theme_section_background)) }
                item(key = "background") {
                    SettingsCard(cardColor) {
                        AnimatedVisibility(visible = showTexture) {
                            SwitchPreference(
                                title = stringResource(R.string.theme_show_texture),
                                checked = showTexture,
                                startAction = { RowIcon(R.drawable.ic_style_24) },
                                onCheckedChange = {
                                    ThemeConfig.setShowTexture(activity, it)
                                    showTexture = it
                                },
                            )
                        }
                        ArrowPreference(
                            title = stringResource(if (hasBackground) R.string.theme_bg_image_replace else R.string.theme_bg_image),
                            summary = stringResource(R.string.theme_bg_image_summary),
                            enabled = !importing,
                            startAction = { RowIcon(R.drawable.ic_image_24) },
                            endActions = {
                                AnimatedVisibility(visible = importing) {
                                    InfiniteProgressIndicator(color = colors.primary)
                                }
                            },
                            onClick = pickImage,
                        )
                        SettingsVisibility(visible = hasBackground) {
                            ArrowPreference(
                                title = stringResource(R.string.theme_bg_image_remove),
                                enabled = !importing,
                                onClick = {
                                    ThemeConfig.clearBackground(activity)
                                    backgroundVersion++
                                },
                            )
                        }
                        SettingsVisibility(visible = hasBackground) {
                            Column {
                        SliderPreference(
                            title = stringResource(R.string.theme_bg_blur),
                            value = bgBlur,
                            valueText = "${bgBlur.roundToInt()} dp",
                            valueRange = 0f..25f,
                            steps = 24,
                            startAction = { RowIcon(R.drawable.ic_blur_24) },
                            onValueChange = { bgBlur = it },
                            onValueChangeFinished = { ThemeConfig.setBgBlur(activity, bgBlur.roundToInt()) },
                        )
                        SliderPreference(
                            title = stringResource(R.string.theme_bg_dim),
                            value = bgDim,
                            valueText = "${bgDim.roundToInt()}%",
                            valueRange = 0f..80f,
                            steps = 79,
                            startAction = { RowIcon(R.drawable.ic_brightness_24) },
                            onValueChange = { bgDim = it },
                            onValueChangeFinished = { ThemeConfig.setBgDim(activity, bgDim.roundToInt()) },
                        )
                        SliderPreference(
                            title = stringResource(R.string.theme_card_alpha),
                            value = cardAlpha,
                            valueText = "${cardAlpha.roundToInt()}%",
                            valueRange = 40f..100f,
                            steps = 59,
                            startAction = { RowIcon(R.drawable.ic_opacity_24) },
                            onValueChange = { cardAlpha = it },
                            onValueChangeFinished = { ThemeConfig.setCardAlpha(activity, cardAlpha.roundToInt()) },
                        )
                            }
                        }
                    }
                }
            }
        }
    }
}

/** Keep dependent rows and section headings in the same visibility transition. */
@Composable
private fun SettingsVisibility(visible: Boolean, content: @Composable () -> Unit) {
    AnimatedVisibility(
        visible = visible,
        enter = expandVertically(tween(250)) + fadeIn(tween(250)),
        exit = shrinkVertically(tween(250)) + fadeOut(tween(200)),
    ) {
        content()
    }
}

/** A group of rows on one card, with the Miuix page margins. */
@Composable
private fun SettingsCard(color: Color, content: @Composable ColumnScope.() -> Unit) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp)
            .padding(bottom = 12.dp)
            .animateContentSize(),
        colors = CardDefaults.defaultColors(color = color),
        content = content,
    )
}

/** The leading icon of a settings row (SukiSU style). */
@Composable
private fun RowIcon(id: Int) {
    Icon(
        imageVector = ImageVector.vectorResource(id),
        contentDescription = null,
        modifier = Modifier.padding(end = 6.dp),
        tint = MiuixTheme.colorScheme.onBackground,
    )
}
