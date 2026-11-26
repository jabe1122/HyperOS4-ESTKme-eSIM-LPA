package io.github.local.esimh4ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.theme.ThemeController

/** Keep the controller's dynamic palette and card colors when using a black page. */
@Composable
fun AppMiuixTheme(controller: ThemeController, content: @Composable () -> Unit) {
    MiuixTheme(controller = controller) {
        val colors = MiuixTheme.colorScheme
        MiuixTheme(
            colors = if (ThemeConfig.amoledBackground && isSystemInDarkTheme())
                colors.copy(surface = Color.Black, background = Color.Black) else colors,
            content = content,
        )
    }
}
