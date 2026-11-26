@file:JvmName("LicensesScreen")

package io.github.local.esimh4probe

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.stringResource
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
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TopAppBar
import top.yukonga.miuix.kmp.blur.layerBackdrop
import top.yukonga.miuix.kmp.blur.rememberLayerBackdrop
import top.yukonga.miuix.kmp.glass.GlassIconButton
import top.yukonga.miuix.kmp.glass.GlassTopAppBar
import io.github.local.esimh4ui.AppIcons
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.theme.ColorSchemeMode
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.theme.ThemeController
import top.yukonga.miuix.kmp.utils.overScrollVertical
import top.yukonga.miuix.kmp.utils.scrollEndHaptic

/**
 * Shows the open source licenses page in the Miuix style, laid out like the Miuix example app's
 * third party licenses page: one card per library ("1.13.0, Apache-2.0"), under the same top bar
 * as the theme page.
 * Tapping a library opens its web page; this project's license text is a separate Compose
 * destination, with predictive back returning to the list.
 * [LicensesActivity] calls this; the Material 3 style uses its View page.
 */
fun setLicensesContent(activity: LicensesActivity) {
    activity.setContent {
        val controller = remember {
            ThemeController(if (ThemeConfig.dynamicColor) ColorSchemeMode.MonetSystem else ColorSchemeMode.System)
        }
        io.github.local.esimh4ui.AppMiuixTheme(controller = controller) {
            val stack = io.github.local.esimh4ui.rememberSettingsBackStack()
            io.github.local.esimh4ui.SettingsNavigation(stack) { page ->
                val navigator = io.github.local.esimh4ui.installerx.navigation.LocalNavigator.current
                LicensesPage(
                    activity = activity,
                    showProject = page == "projectLicense",
                    onBack = { if (page == "root") activity.finish() else navigator.pop() },
                    onOpenProject = { navigator.push(io.github.local.esimh4ui.SettingsRoute("projectLicense")) },
                )
            }
        }
    }
}

@Composable
internal fun LicensesPage(
    activity: android.app.Activity,
    showProject: Boolean,
    onBack: () -> Unit,
    onOpenProject: () -> Unit,
) {
    val data by produceState<LicenseData?>(null) {
        value = withContext(Dispatchers.IO) { LicenseData.load(activity) }
    }
    // Each navigation entry retains its own scroll state.
    val listState = rememberLazyListState()
    val textState = rememberLazyListState()
    val loaded = data
    val entry = if (showProject) loaded?.project else null

    val openFailed = stringResource(R.string.snack_open_link_failed)
    val open: (String) -> Unit = { url ->
        try {
            activity.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        } catch (e: ActivityNotFoundException) {
            Toast.makeText(activity, openFailed, Toast.LENGTH_SHORT).show()
        }
    }

    PageScaffold(
        title = entry?.title ?: stringResource(R.string.licenses_title),
        onBack = onBack,
        listState = if (showProject) textState else listState,
    ) { padding, scrollModifier ->
        if (showProject && entry == null) return@PageScaffold
        if (entry != null) {
            LazyColumn(state = textState, modifier = scrollModifier, contentPadding = padding) {
                item(key = "license") {
                    LicenseCard {
                        Text(
                            text = entry.text ?: stringResource(R.string.licenses_no_text, entry.licenses),
                            modifier = Modifier.padding(16.dp),
                            color = MiuixTheme.colorScheme.onSurface,
                            fontSize = 13.sp,
                        )
                    }
                }
                val url = entry.url
                if (url != null) {
                    item(key = "web") {
                        LicenseCard {
                            ArrowPreference(
                                title = stringResource(R.string.licenses_open_web),
                                summary = url,
                                onClick = { open(url) },
                            )
                        }
                    }
                }
            }
        } else {
            LazyColumn(state = listState, modifier = scrollModifier, contentPadding = padding) {
                val list = loaded ?: return@LazyColumn
                item(key = "projectTitle") { SmallTitle(text = stringResource(R.string.licenses_section_project)) }
                item(key = "project") {
                    LicenseCard {
                        ArrowPreference(
                            title = list.project.title,
                            summary = list.project.summary,
                            onClick = onOpenProject,
                        )
                    }
                }
                item(key = "librariesTitle") { SmallTitle(text = stringResource(R.string.licenses_section_libraries)) }
                // One card per library, like the Miuix example
                itemsIndexed(list.libraries, key = { index, _ -> "lib$index" }) { _, lib ->
                    val url = lib.url
                    LicenseCard {
                        ArrowPreference(
                            title = lib.title,
                            summary = lib.summary,
                            enabled = url != null,
                            onClick = { if (url != null) open(url) },
                        )
                    }
                }
            }
        }
    }
}

/**
 * Miuix scaffold with the same top bar as the theme page: a large title that collapses into a
 * small centered one on scroll. With the glass bar setting it is a glass bar (transparent at rest;
 * once the page scrolls, the back button gets its glass pill and the band fades in, with a
 * progressive blur if enabled); otherwise the plain Miuix bar.
 */
@Composable
private fun PageScaffold(
    title: String,
    onBack: () -> Unit,
    listState: LazyListState,
    content: @Composable (PaddingValues, Modifier) -> Unit,
) {
    val colors = MiuixTheme.colorScheme
    val scrollBehavior = MiuixScrollBehavior()
    // What the glass bar samples: the page behind it
    val backdrop = rememberLayerBackdrop()
    val back = stringResource(io.github.local.esimh4ui.R.string.ui_back)
    Scaffold(
        containerColor = colors.surface,
        topBar = {
            if (ThemeConfig.useGlass()) {
                GlassTopAppBar(
                    title = title,
                    backdrop = backdrop,
                    isContentScrolled = listState.canScrollBackward,
                    scrollBehavior = scrollBehavior,
                    progressiveBlur = ThemeConfig.progressiveBlur,
                    navigationIcon = {
                        GlassIconButton(onClick = onBack) {
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
                        IconButton(onClick = onBack) {
                            Icon(
                                imageVector = AppIcons.ChevronBackward,
                                contentDescription = back,
                                tint = colors.onBackground,
                            )
                        }
                    },
                )
            }
        },
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .layerBackdrop(backdrop)
                .background(colors.surface),
        ) {
            content(
                PaddingValues(
                    top = innerPadding.calculateTopPadding(),
                    bottom = innerPadding.calculateBottomPadding() + 12.dp,
                ),
                Modifier
                    .fillMaxSize()
                    .scrollEndHaptic()
                    .overScrollVertical(onOffsetChanged = { scrollBehavior.state.overscrollOffset = it })
                    .nestedScroll(scrollBehavior.nestedScrollConnection),
            )
        }
    }
}

@Composable
private fun LicenseCard(content: @Composable ColumnScope.() -> Unit) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp)
            .padding(bottom = 12.dp),
        colors = CardDefaults.defaultColors(color = MiuixTheme.colorScheme.surfaceContainer),
        content = content,
    )
}
