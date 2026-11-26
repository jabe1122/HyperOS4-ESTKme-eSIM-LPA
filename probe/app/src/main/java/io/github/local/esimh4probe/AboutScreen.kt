@file:JvmName("AboutScreen")

package io.github.local.esimh4probe

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState

import android.content.ActivityNotFoundException
import android.content.Intent
import android.os.SystemClock
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.setContent
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.local.esimh4ui.ThemeConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.CardDefaults
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.MiuixScrollBehavior
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.SmallTopAppBar
import top.yukonga.miuix.kmp.basic.Text
import io.github.local.esimh4ui.AppIcons
import top.yukonga.miuix.kmp.blur.layerBackdrop
import top.yukonga.miuix.kmp.blur.rememberLayerBackdrop
import top.yukonga.miuix.kmp.glass.GlassIconButton
import top.yukonga.miuix.kmp.glass.GlassTopAppBar
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.preference.SwitchPreference
import top.yukonga.miuix.kmp.preference.SliderPreference
import top.yukonga.miuix.kmp.window.WindowBottomSheet
import top.yukonga.miuix.kmp.theme.ColorSchemeMode
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.theme.ThemeController
import top.yukonga.miuix.kmp.utils.overScrollVertical
import top.yukonga.miuix.kmp.utils.scrollEndHaptic

/**
 * Shows the About page in the Miuix style, laid out like the About page of the Miuix example app:
 * the HyperOS background effect behind the app icon, name and version, then cards for the
 * repository, the licenses and build, and the authors. Scrolling fades the header and the effect
 * out and the bar title in. [AboutActivity] calls this; the Material 3 style uses [AboutSection].
 */
fun setAboutContent(activity: AboutActivity, isDark: Boolean) {
    activity.setContent {
        val controller = remember {
            ThemeController(if (ThemeConfig.dynamicColor) ColorSchemeMode.MonetSystem else ColorSchemeMode.System)
        }
        io.github.local.esimh4ui.AppMiuixTheme(controller = controller) {
            val stack = io.github.local.esimh4ui.rememberSettingsBackStack()
            io.github.local.esimh4ui.SettingsNavigation(stack) { page ->
                val navigator = io.github.local.esimh4ui.installerx.navigation.LocalNavigator.current
                when (page) {
                    "licenses", "projectLicense" -> LicensesPage(
                        activity = activity,
                        showProject = page == "projectLicense",
                        onBack = { navigator.pop() },
                        onOpenProject = { navigator.push(io.github.local.esimh4ui.SettingsRoute("projectLicense")) },
                    )
                    else -> AboutPage(
                        activity = activity,
                        isDark = isDark,
                        onBack = { activity.finish() },
                        onOpenLicenses = { navigator.push(io.github.local.esimh4ui.SettingsRoute("licenses")) },
                    )
                }
            }
        }
    }
}

@Composable
internal fun AboutPage(
    activity: android.app.Activity,
    isDark: Boolean,
    onBack: () -> Unit,
    onOpenLicenses: () -> Unit,
) {
    val haptic = androidx.compose.ui.platform.LocalHapticFeedback.current
    val exportAction = remember(activity) { AboutLogExport(activity) }
    val colors = MiuixTheme.colorScheme
    val info by produceState<AboutInfo?>(null) {
        value = withContext(Dispatchers.IO) { AboutInfo.load(activity) }
    }
    val listState = rememberLazyListState()
    val backdrop = rememberLayerBackdrop()
    val scrollBehavior = MiuixScrollBehavior()
    val texture = remember { TextureSettings() }
    var showTextureSet by remember { mutableStateOf(false) }
    var textureUnlocked by remember { mutableStateOf(ThemeConfig.showTexture) }
    var copyrightTaps by remember { mutableIntStateOf(0) }
    var lastCopyrightTap by remember { mutableLongStateOf(0L) }

    // 0 while the header is fully visible, 1 once it has scrolled away.
    val progress by remember {
        derivedStateOf {
            if (listState.firstVisibleItemIndex > 0) {
                1f
            } else {
                val header = listState.layoutInfo.visibleItemsInfo.firstOrNull { it.key == "header" }
                if (header != null && header.size > 0) {
                    (listState.firstVisibleItemScrollOffset.toFloat() / header.size).coerceIn(0f, 1f)
                } else {
                    0f
                }
            }
        }
    }
    val collapsed by remember { derivedStateOf { progress >= 0.6f } }
    val titleAlpha by animateFloatAsState(if (collapsed) 1f else 0f, tween(200), label = "title")
    val topBarColor by animateColorAsState(if (collapsed) colors.surface else Color.Transparent, tween(200), label = "aboutTopBar")

    val openFailed = stringResource(R.string.snack_open_link_failed)
    val open: (String) -> Unit = { url ->
        try {
            activity.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        } catch (e: ActivityNotFoundException) {
            Toast.makeText(activity, openFailed, Toast.LENGTH_SHORT).show()
        }
    }

    Box(Modifier.fillMaxSize()) {
        Scaffold(
            containerColor = Color.Transparent,
            topBar = {
                if (ThemeConfig.useGlass()) {
                    GlassTopAppBar(
                        title = stringResource(R.string.section_about),
                        expandable = false,
                        backdrop = backdrop,
                        scrollBehavior = scrollBehavior,
                        progressiveBlur = ThemeConfig.progressiveBlur,
                        titleAlpha = { titleAlpha },
                        navigationIcon = {
                            GlassIconButton(onClick = onBack) {
                                Icon(
                                    imageVector = AppIcons.Back,
                                    contentDescription = stringResource(io.github.local.esimh4ui.R.string.ui_back),
                                    modifier = Modifier.size(28.dp),
                                    tint = colors.onSurface,
                                )
                            }
                        },
                    )
                } else {
                SmallTopAppBar(
                    title = stringResource(R.string.section_about),
                    color = topBarColor,
                    titleColor = colors.onSurface.copy(alpha = titleAlpha),
                    scrollBehavior = scrollBehavior,
                    navigationIcon = {
                        IconButton(onClick = onBack) {
                            Icon(
                                imageVector = AppIcons.Back,
                                contentDescription = stringResource(io.github.local.esimh4ui.R.string.ui_back),
                                tint = colors.onBackground,
                            )
                        }
                    },
                )
                }
            },
        ) { innerPadding ->
            // Record the animated background and list together, excluding the glass bar itself.
            BgEffectBackground(
                isDark = isDark,
                surface = colors.surface,
                modifier = Modifier.fillMaxSize().layerBackdrop(backdrop),
                alpha = { 1f - progress },
                settings = texture,
            ) { _ ->
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
                item(key = "header") {
                    Header(
                        versionText = info?.let { "v" + it.versionText() } ?: "",
                        isDark = isDark,
                        progress = { progress },
                        onClick = { exportAction.onHeaderTap() },
                    )
                }
                item(key = "checkUpdate") {
                    AboutCard {
                        ArrowPreference(
                            title = stringResource(R.string.update_check),
                            onClick = { UpdateChecker.check(activity) },
                        )
                        AnimatedVisibility(visible = textureUnlocked) {
                            ArrowPreference(
                                title = stringResource(R.string.texture_set_title),
                                onClick = { showTextureSet = true },
                            )
                        }
                    }
                }
                val data = info ?: return@LazyColumn
                // Match the demo: the details occupy at least one viewport so the header
                // can scroll completely away even when there are only a few contributors.
                item(key = "details") {
                    Box {
                        Spacer(Modifier.fillParentMaxHeight())
                        Column {
                            AboutCard {
                                ArrowPreference(
                                    title = stringResource(R.string.about_repo),
                                    summary = data.repo,
                                    endActions = {
                                        EndText(stringResource(if (data.fork) R.string.about_repo_fork else R.string.about_repo_original))
                                    },
                                    onClick = { open(data.repoUrl) },
                                )
                                if (data.fork && data.parent != null) {
                                    ArrowPreference(
                                        title = stringResource(R.string.about_upstream),
                                        summary = data.parent,
                                        onClick = { open(data.parentUrl) },
                                    )
                                }
                            }
                            AboutCard {
                                ArrowPreference(
                                    title = stringResource(R.string.licenses_title),
                                    onClick = onOpenLicenses,
                                )
                                val commit = data.shortCommit()
                                if (commit != null) {
                                    ArrowPreference(
                                        title = stringResource(R.string.about_commit),
                                        endActions = { EndText(commit) },
                                        onClick = { open(data.commitUrl()) },
                                    )
                                }
                            }
                            SmallTitle(text = stringResource(R.string.section_authors))
                            AboutCard {
                                for (person in data.people) {
                                    ArrowPreference(
                                        title = person.name,
                                        summary = AboutSection.personSummary(activity, person),
                                        startAction = { Avatar(person) },
                                        onClick = { open(person.url) },
                                    )
                                }
                            }
                            Text(
                                text = stringResource(R.string.about_copyright, data.years()),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable(interactionSource = null, indication = null) {
                                        haptic.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.ContextClick)
                                        if (ThemeConfig.showTexture) {
                                            Toast.makeText(activity, io.github.local.esimh4ui.R.string.theme_texture_no_need, Toast.LENGTH_SHORT).show()
                                            return@clickable
                                        }
                                        val now = SystemClock.elapsedRealtime()
                                        copyrightTaps = if (now - lastCopyrightTap <= 1500L) copyrightTaps + 1 else 1
                                        lastCopyrightTap = now
                                        if (copyrightTaps >= 7) {
                                            copyrightTaps = 0
                                            ThemeConfig.setShowTexture(activity, true)
                                            textureUnlocked = true
                                            showTextureSet = true
                                        }
                                    }
                                    .padding(horizontal = 24.dp, vertical = 8.dp),
                                color = colors.onSurfaceVariantSummary,
                                fontSize = 12.sp,
                                textAlign = TextAlign.Center,
                            )
                        }
                    }
                }
            }
        }
    }
    }
    TextureSetSheet(showTextureSet, texture) { showTextureSet = false }
}

@Composable
private fun TextureSetSheet(show: Boolean, settings: TextureSettings, onDismiss: () -> Unit) {
    WindowBottomSheet(
        show = show,
        title = stringResource(R.string.texture_set_title),
        onDismissRequest = onDismiss,
        insideMargin = DpSize(0.dp, 0.dp),
    ) {
        LazyColumn {
            item {
                Text(
                    stringResource(R.string.texture_set_summary),
                    modifier = Modifier.padding(horizontal = 24.dp, vertical = 12.dp),
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                )
                top.yukonga.miuix.kmp.preference.WindowDropdownPreference(
                    title = stringResource(io.github.local.esimh4ui.R.string.theme_miuix_version),
                    items = listOf("OS2", "OS3"),
                    selectedIndex = settings.version - 2,
                    onSelectedIndexChange = { settings.version = it + 2 },
                )
                SwitchPreference(
                    title = stringResource(R.string.texture_background),
                    checked = settings.enabled,
                    onCheckedChange = { settings.enabled = it },
                )
                SwitchPreference(
                    title = stringResource(R.string.texture_animation),
                    checked = settings.animated,
                    onCheckedChange = { settings.animated = it },
                )
                SwitchPreference(
                    title = stringResource(R.string.texture_full_screen),
                    checked = settings.fullScreen,
                    onCheckedChange = { settings.fullScreen = it },
                )
                SliderPreference(
                    title = stringResource(R.string.texture_noise),
                    value = settings.noise,
                    valueText = "${(settings.noise * 100).toInt()}%",
                    valueRange = 0f..2f,
                    onValueChange = { settings.noise = it },
                )
                SliderPreference(
                    title = stringResource(R.string.texture_brightness),
                    value = settings.brightness,
                    valueText = "${(settings.brightness * 100).toInt()}%",
                    valueRange = -0.5f..0.5f,
                    onValueChange = { settings.brightness = it },
                )
                SliderPreference(
                    title = stringResource(R.string.texture_saturation),
                    value = settings.saturation,
                    valueText = "${(settings.saturation * 100).toInt()}%",
                    valueRange = 0f..2f,
                    onValueChange = { settings.saturation = it },
                )
                ArrowPreference(
                    title = stringResource(R.string.texture_reset),
                    onClick = { settings.reset() },
                )
                Spacer(Modifier.height(WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()))
            }
        }
    }
}

/** App icon, name (with a gradient, like the Miuix example) and version; fades out on scroll. */
@Composable
private fun Header(versionText: String, isDark: Boolean, progress: () -> Float, onClick: () -> Unit) {
    val nameBrush = remember(isDark) {
        Brush.linearGradient(
            if (isDark) {
                listOf(Color(0xFFC3B8FF), Color(0xFFF2B6D9))
            } else {
                listOf(Color(0xFF3B2DB8), Color(0xFFA0609E))
            },
        )
    }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 72.dp, bottom = 112.dp)
            .graphicsLayer {
                val p = (progress() / 0.5f).coerceIn(0f, 1f)
                alpha = 1f - p
                scaleX = 1f - p * 0.05f
                scaleY = 1f - p * 0.05f
            },
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(
            imageVector = ImageVector.vectorResource(R.drawable.ic_launcher_monochrome),
            contentDescription = null,
            tint = MiuixTheme.colorScheme.onSurface,
            modifier = Modifier.size(160.dp)
                .clickable(interactionSource = null, indication = null, onClick = onClick)
                .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
                .drawWithContent {
                    drawContent()
                    // Use the name's contrast-aware gradient, masked to the glyph.
                    drawRect(nameBrush, blendMode = BlendMode.SrcIn)
                },
        )
        BasicText(
            text = stringResource(R.string.app_name),
            modifier = Modifier.clickable(interactionSource = null, indication = null, onClick = onClick).padding(start = 24.dp, end = 24.dp, top = 14.dp, bottom = 6.dp),
            style = TextStyle(
                brush = nameBrush,
                fontSize = 30.sp,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center,
            ),
        )
        Text(
            text = versionText,
            modifier = Modifier.clickable(interactionSource = null, indication = null, onClick = onClick),
            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            fontSize = 14.sp,
            textAlign = TextAlign.Center,
        )
    }
}

/** Translucent card, so the background effect shows through while it is visible. */
@Composable
private fun AboutCard(content: @Composable ColumnScope.() -> Unit) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp)
            .padding(bottom = 12.dp),
        colors = CardDefaults.defaultColors(color = MiuixTheme.colorScheme.surfaceContainer.copy(alpha = 0.7f)),
        content = content,
    )
}

@Composable
private fun EndText(text: String) {
    Text(
        text = text,
        fontSize = MiuixTheme.textStyles.body2.fontSize,
        color = MiuixTheme.colorScheme.onSurfaceVariantActions,
    )
}

/** The contributor's GitHub avatar, or a person icon for a build without the workflow data. */
@Composable
private fun Avatar(person: AboutInfo.Person) {
    val modifier = Modifier
        .padding(end = 12.dp)
        .size(40.dp)
        .clip(CircleShape)
    val avatar = person.avatar
    if (avatar != null) {
        val bitmap = remember(avatar) { avatar.asImageBitmap() }
        Image(bitmap = bitmap, contentDescription = null, contentScale = ContentScale.Crop, modifier = modifier)
    } else {
        Box(modifier.background(MiuixTheme.colorScheme.secondaryContainer), contentAlignment = Alignment.Center) {
            Icon(
                imageVector = ImageVector.vectorResource(R.drawable.ic_person_24),
                contentDescription = null,
                modifier = Modifier.size(24.dp),
                tint = MiuixTheme.colorScheme.onSecondaryContainer,
            )
        }
    }
}
