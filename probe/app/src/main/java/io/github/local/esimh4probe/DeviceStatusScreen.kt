@file:JvmName("DeviceStatusScreen")

package io.github.local.esimh4probe

import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import io.github.local.esimh4ui.MiuixCard
import io.github.local.esimh4ui.MiuixInfoLine
import io.github.local.esimh4ui.MiuixPage
import io.github.local.esimh4ui.ThemeConfig
import kotlinx.coroutines.delay
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.theme.ColorSchemeMode
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.theme.ThemeController

/**
 * The device and eUICC status page in the Miuix style, with Miuix's pull to refresh.
 * [DeviceStatusActivity] calls this; the Material 3 style uses its View page. Both show
 * [DeviceStatusActivity.read].
 */
fun setDeviceStatusContent(activity: DeviceStatusActivity) {
    activity.setContent {
        val controller = remember {
            ThemeController(if (ThemeConfig.dynamicColor) ColorSchemeMode.MonetSystem else ColorSchemeMode.System)
        }
        io.github.local.esimh4ui.AppMiuixTheme(controller = controller) {
            var sections by remember { mutableStateOf(DeviceStatusActivity.read(activity)) }
            var refreshing by remember { mutableStateOf(false) }
            // onResume reads the values again (back from another app or the settings).
            activity.composeRefresh = Runnable { sections = DeviceStatusActivity.read(activity) }
            LaunchedEffect(refreshing) {
                if (refreshing) {
                    sections = DeviceStatusActivity.read(activity)
                    // The values are read at once; keep the indicator visible briefly.
                    delay(400)
                    refreshing = false
                }
            }
            val ok = Color(activity.paletteColors().success)
            val bad = Color(activity.paletteColors().error)
            MiuixPage(
                title = activity.getString(R.string.row_device_status),
                onBack = { activity.finish() },
                bottomPadding = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding(),
                isRefreshing = refreshing,
                onRefresh = { refreshing = true },
            ) {
                sections.forEachIndexed { index, section ->
                    item(key = "title$index") { SmallTitle(text = section.title) }
                    item(key = "card$index") {
                        MiuixCard {
                            for (line in section.lines) {
                                MiuixInfoLine(
                                    label = line.label,
                                    value = line.value,
                                    valueColor = line.ok?.let { if (it) ok else bad },
                                    mono = line.mono,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
