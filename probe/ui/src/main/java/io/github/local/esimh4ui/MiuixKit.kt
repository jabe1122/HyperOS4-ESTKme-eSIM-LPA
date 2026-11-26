@file:JvmName("MiuixKit")

package io.github.local.esimh4ui

import androidx.compose.animation.animateContentSize

import androidx.annotation.DrawableRes
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.BlurredEdgeTreatment
import androidx.compose.ui.draw.blur
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.CardDefaults
import top.yukonga.miuix.kmp.basic.FloatingNavigationBar
import top.yukonga.miuix.kmp.basic.FloatingNavigationBarItem
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.MiuixScrollBehavior
import top.yukonga.miuix.kmp.basic.NavigationBar
import top.yukonga.miuix.kmp.basic.NavigationBarItem
import top.yukonga.miuix.kmp.basic.PullToRefresh
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TopAppBar
import top.yukonga.miuix.kmp.blur.LayerBackdrop
import top.yukonga.miuix.kmp.blur.layerBackdrop
import top.yukonga.miuix.kmp.blur.rememberLayerBackdrop
import top.yukonga.miuix.kmp.glass.GlassIconButton
import top.yukonga.miuix.kmp.glass.GlassTopAppBar
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.utils.PressFeedbackType
import top.yukonga.miuix.kmp.utils.overScrollVertical
import top.yukonga.miuix.kmp.utils.scrollEndHaptic

/*
 * Shared pieces of the Miuix Compose pages (main screen, device status), so they follow the
 * same theme settings as the theme page: glass or plain top bar, page background, card opacity
 * and the bottom bar style.
 */

/**
 * A page with a Miuix top bar (large title that collapses on scroll; glass when the theme asks
 * for it) over a [LazyColumn]. With [onRefresh] the list gets Miuix's pull to refresh; the
 * caller sets [isRefreshing] to true in [onRefresh] and back to false when the work is done.
 *
 * @param bottomPadding space to keep free under the list (bottom bar, navigation bar).
 * @param drawBackground false when a parent already draws the page color and background image.
 */
@Composable
fun MiuixPage(
    title: String,
    modifier: Modifier = Modifier,
    onBack: (() -> Unit)? = null,
    bottomPadding: Dp = 0.dp,
    drawBackground: Boolean = true,
    isRefreshing: Boolean = false,
    onRefresh: (() -> Unit)? = null,
    actions: @Composable RowScope.() -> Unit = {},
    content: LazyListScope.() -> Unit,
) {
    val colors = MiuixTheme.colorScheme
    // What the glass top bar samples: this page's list (and its background, when drawn here).
    val backdrop = rememberLayerBackdrop()
    val scrollBehavior = MiuixScrollBehavior()
    val listState = rememberLazyListState()
    val back = stringResource(R.string.ui_back)

    Scaffold(
        modifier = modifier,
        containerColor = Color.Transparent,
        // The top bar pads for the status bar itself; the caller passes the bottom space.
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            if (ThemeConfig.useGlass()) {
                GlassTopAppBar(
                    title = title,
                    backdrop = backdrop,
                    isContentScrolled = listState.canScrollBackward,
                    scrollBehavior = scrollBehavior,
                    progressiveBlur = ThemeConfig.progressiveBlur,
                    navigationIcon = {
                        if (onBack != null) {
                            GlassIconButton(onClick = onBack) {
                                Icon(
                                    imageVector = AppIcons.ChevronBackward,
                                    contentDescription = back,
                                    modifier = Modifier.size(28.dp),
                                    tint = colors.onSurface,
                                )
                            }
                        }
                    },
                    actions = actions,
                )
            } else {
                TopAppBar(
                    title = title,
                    color = colors.surface,
                    scrollBehavior = scrollBehavior,
                    navigationIcon = {
                        if (onBack != null) {
                            IconButton(onClick = onBack) {
                                Icon(
                                    imageVector = AppIcons.ChevronBackward,
                                    contentDescription = back,
                                    tint = colors.onBackground,
                                )
                            }
                        }
                    },
                    actions = actions,
                )
            }
        },
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .layerBackdrop(backdrop)
                .then(if (drawBackground) Modifier.background(colors.surface) else Modifier),
        ) {
            if (drawBackground) PageBackground()
            val top = innerPadding.calculateTopPadding()
            val list: @Composable () -> Unit = {
                LazyColumn(
                    state = listState,
                    modifier = Modifier
                        .fillMaxSize()
                        .scrollEndHaptic()
                        .overScrollVertical(onOffsetChanged = { scrollBehavior.state.overscrollOffset = it })
                        .nestedScroll(scrollBehavior.nestedScrollConnection),
                    contentPadding = PaddingValues(top = top, bottom = bottomPadding + 12.dp),
                    content = content,
                )
            }
            if (onRefresh != null) {
                PullToRefresh(
                    isRefreshing = isRefreshing,
                    onRefresh = onRefresh,
                    contentPadding = PaddingValues(top = top),
                    topAppBarScrollBehavior = scrollBehavior,
                    refreshTexts = listOf(
                        stringResource(R.string.ptr_pull),
                        stringResource(R.string.ptr_release),
                        stringResource(R.string.ptr_refreshing),
                        stringResource(R.string.ptr_done),
                    ),
                    content = list,
                )
            } else {
                list()
            }
        }
    }
}

/** The custom page background (theme settings): image with blur, dimmed toward the page color. */
@Composable
fun PageBackground() {
    val context = LocalContext.current
    val image = remember { ThemeConfig.loadBackground(context)?.asImageBitmap() } ?: return
    val colors = MiuixTheme.colorScheme
    Image(
        bitmap = image,
        contentDescription = null,
        contentScale = ContentScale.Crop,
        modifier = Modifier
            .fillMaxSize()
            .then(
                if (ThemeConfig.bgBlur >= 1) {
                    Modifier.blur(ThemeConfig.bgBlur.dp, BlurredEdgeTreatment.Rectangle)
                } else {
                    Modifier
                },
            ),
    )
    Box(
        Modifier
            .fillMaxSize()
            .background(colors.surface.copy(alpha = ThemeConfig.bgDim.coerceIn(0, 90) / 100f)),
    )
}

/** True when the theme has a custom background image (cards then use the card opacity). */
@Composable
fun rememberHasBackground(): Boolean {
    val context = LocalContext.current
    return remember { ThemeConfig.hasBackground(context) }
}

/** Card color: the Miuix surface container, see-through over a custom background. */
@Composable
fun miuixCardColor(): Color {
    val color = MiuixTheme.colorScheme.surfaceContainer
    return if (rememberHasBackground()) color.copy(alpha = ThemeConfig.cardAlpha / 100f) else color
}

/** A group of rows on one card, with the Miuix page margins. */
@Composable
fun MiuixCard(
    modifier: Modifier = Modifier,
    insideMargin: PaddingValues = CardDefaults.InsideMargin,
    color: Color = miuixCardColor(),
    pressFeedbackType: PressFeedbackType = PressFeedbackType.None,
    onClick: (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    Card(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp)
            .padding(bottom = 12.dp)
            .animateContentSize(),
        insideMargin = insideMargin,
        colors = CardDefaults.defaultColors(color = color),
        pressFeedbackType = pressFeedbackType,
        onClick = onClick,
        content = content,
    )
}

/** The leading icon of a settings row (SukiSU style). */
@Composable
fun MiuixRowIcon(@DrawableRes id: Int) {
    Icon(
        imageVector = ImageVector.vectorResource(id),
        contentDescription = null,
        modifier = Modifier.padding(end = 6.dp),
        tint = MiuixTheme.colorScheme.onBackground,
    )
}

/** A label / value line; [valueColor] null uses the summary color. */
@Composable
fun MiuixInfoLine(label: String, value: String, valueColor: Color? = null, mono: Boolean = false) {
    val colors = MiuixTheme.colorScheme
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            modifier = Modifier.padding(end = 16.dp),
            color = colors.onSurface,
            fontSize = 15.sp,
        )
        Text(
            text = value,
            modifier = Modifier.weight(1f),
            color = valueColor ?: colors.onSurfaceVariantSummary,
            fontSize = 14.sp,
            fontFamily = if (mono) FontFamily.Monospace else null,
            textAlign = TextAlign.End,
        )
    }
}

/** States of [MiuixStatusCard]. */
enum class StatusMode { LOADING, OK, ERROR }

/**
 * The SukiSU-style status card (the Compose twin of [StatusBanner] in the Miuix style): green
 * with a check mark and tags when working, a plain card with a red hint and an optional
 * action otherwise.
 */
@Composable
fun MiuixStatusCard(
    palette: UiKit.Palette,
    mode: StatusMode,
    title: String,
    text: String? = null,
    tags: List<StatusBanner.Tag> = emptyList(),
    actionText: String? = null,
    onAction: (() -> Unit)? = null,
    onClick: (() -> Unit)? = null,
) {
    val dynamic = ThemeConfig.dynamicColor
    val okBg = Color(if (dynamic) palette.primaryContainer else if (palette.isNight) 0xFF1A3825.toInt() else 0xFFDFFAE4.toInt())
    val okAccent = Color(if (dynamic) palette.primary else 0xFF36D167.toInt())
    val bg = if (mode == StatusMode.OK) okBg else miuixCardColor()
    val haptic = LocalHapticFeedback.current
    MiuixCard(
        modifier = Modifier.padding(top = 8.dp),
        insideMargin = PaddingValues(0.dp),
        color = bg,
        pressFeedbackType = PressFeedbackType.Tilt,
        onClick = {
            haptic.performHapticFeedback(HapticFeedbackType.ContextClick)
            onClick?.invoke()
        },
    ) {
        Box(Modifier.fillMaxWidth().heightIn(min = 188.dp)) {
            if (mode == StatusMode.OK) {
                Icon(
                    imageVector = ImageVector.vectorResource(R.drawable.ic_check_circle_outline_24),
                    contentDescription = null,
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .offset(x = 27.dp, y = 31.dp)
                        .size(150.dp),
                    tint = okAccent.copy(alpha = if (dynamic) 0.8f else 1f),
                )
            }
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 188.dp)
                    .padding(22.dp),
            ) {
                when (mode) {
                    StatusMode.LOADING -> {
                        Spacer(Modifier.weight(1f))
                        BigTitle(title, Color(palette.onSurface))
                    }
                    StatusMode.OK -> {
                        Row {
                            for (tag in tags) StatusTag(palette, tag, okAccent)
                        }
                        Spacer(Modifier.weight(1f))
                        BigTitle(title, Color(palette.onSurface))
                        if (text != null) {
                            Text(
                                text = text,
                                modifier = Modifier.padding(top = 4.dp),
                                color = Color(palette.onSurfaceVariant),
                                fontSize = 14.sp,
                                fontFamily = FontFamily.Monospace,
                            )
                        }
                    }
                    StatusMode.ERROR -> {
                        val accent = Color(palette.error)
                        if (text != null) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(
                                    imageVector = ImageVector.vectorResource(R.drawable.ic_error_outline_24),
                                    contentDescription = null,
                                    modifier = Modifier.padding(end = 6.dp).size(18.dp),
                                    tint = accent,
                                )
                                Text(text = text, color = accent, fontSize = 14.sp)
                            }
                        }
                        Spacer(Modifier.weight(1f))
                        BigTitle(title, Color(palette.onSurface), Modifier.padding(vertical = 12.dp))
                        if (actionText != null && onAction != null) {
                            Button(onClick = onAction, colors = ButtonDefaults.buttonColorsPrimary()) {
                                Text(text = actionText, color = MiuixTheme.colorScheme.onPrimary)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun BigTitle(text: String, color: Color, modifier: Modifier = Modifier) {
    Text(text = text, modifier = modifier, color = color, fontSize = 36.sp, fontWeight = FontWeight.Bold)
}

/** SukiSU StatusTag: 6dp corners, 4/2dp padding, 9sp heavy text. */
@Composable
private fun StatusTag(palette: UiKit.Palette, tag: StatusBanner.Tag, okAccent: Color) {
    val dynamic = ThemeConfig.dynamicColor
    val bg: Color
    val fg: Color
    if (tag.accent) {
        bg = okAccent
        fg = if (dynamic) Color(palette.onPrimary) else Color.White
    } else if (dynamic) {
        bg = Color(palette.secondaryContainer)
        fg = Color(palette.onSecondaryContainer)
    } else {
        bg = Color(if (palette.isNight) 0xFF315D3E.toInt() else 0xFFB8E8C5.toInt())
        fg = Color(if (palette.isNight) 0xFFB8E8C5.toInt() else 0xFF164A29.toInt())
    }
    Text(
        text = tag.label,
        modifier = Modifier
            .padding(end = 6.dp)
            .background(bg, RoundedCornerShape(6.dp))
            .padding(horizontal = 4.dp, vertical = 2.dp),
        color = fg,
        fontSize = 9.sp,
        fontWeight = FontWeight.W700,
        maxLines = 1,
    )
}

/** A bottom bar tab. */
class BarItem(val label: String, @DrawableRes val icon: Int)

/**
 * The bottom bar in the theme's style: the floating capsule (liquid glass when enabled, see
 * [LiquidBottomBar]) or the docked bar (see-through when frosted glass is on).
 *
 * @param backdrop what the liquid glass bar samples: the pages, recorded with [layerBackdrop].
 */
@Composable
fun MiuixBottomBar(items: List<BarItem>, selected: Int, onSelect: (Int) -> Unit, backdrop: LayerBackdrop) {
    val colors = MiuixTheme.colorScheme
    when {
        ThemeConfig.barFloating && ThemeConfig.useLiquidGlass() -> {
            androidx.compose.runtime.key(items.size) {
                LiquidBottomBar(items, selected, onSelect, backdrop)
            }
        }
        ThemeConfig.barFloating -> {
            FloatingNavigationBar {
                items.forEachIndexed { index, item ->
                    FloatingNavigationBarItem(
                        selected = index == selected,
                        onClick = { onSelect(index) },
                        icon = ImageVector.vectorResource(item.icon),
                        label = item.label,
                    )
                }
            }
        }
        else -> {
            NavigationBar(
                color = if (ThemeConfig.useGlass()) colors.surface.copy(alpha = 0.87f) else colors.surface,
            ) {
                items.forEachIndexed { index, item ->
                    NavigationBarItem(
                        selected = index == selected,
                        onClick = { onSelect(index) },
                        icon = ImageVector.vectorResource(item.icon),
                        label = item.label,
                    )
                }
            }
        }
    }
}
