package io.github.local.esimh4ui

import androidx.compose.runtime.Composable
import top.yukonga.miuix.kmp.blur.LayerBackdrop
import top.yukonga.miuix.kmp.glass.GlassDropdownPreference
import top.yukonga.miuix.kmp.preference.WindowDropdownPreference

/** Uses glass dropdowns only for the OS4 appearance. */
@Composable
fun VersionedDropdownPreference(
    title: String,
    items: List<String>,
    selectedIndex: Int,
    backdrop: LayerBackdrop,
    onSelectedIndexChange: (Int) -> Unit,
    summary: String? = null,
    startAction: (@Composable () -> Unit)? = null,
) {
    if (ThemeConfig.miuixVersion == 4) {
        GlassDropdownPreference(
            title = title,
            summary = summary,
            items = items,
            selectedIndex = selectedIndex,
            backdrop = backdrop,
            startAction = startAction,
            onSelectedIndexChange = onSelectedIndexChange,
        )
    } else {
        WindowDropdownPreference(
            title = title,
            summary = summary,
            items = items,
            selectedIndex = selectedIndex,
            startAction = startAction,
            onSelectedIndexChange = onSelectedIndexChange,
        )
    }
}
