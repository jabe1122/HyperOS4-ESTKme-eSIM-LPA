package io.github.local.esimh4ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.local.esimh4ui.installerx.FloatingBottomBar
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.blur.LayerBackdrop

/** App adapter for InstallerX Revived's GPL-3.0 floating bar. */
@Composable
internal fun LiquidBottomBar(items: List<BarItem>, selected: Int, onSelect: (Int) -> Unit, backdrop: LayerBackdrop) {
    if (items.isEmpty()) return
    Box(
        Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 28.dp, vertical = 8.dp),
        contentAlignment = Alignment.Center,
    ) {
        FloatingBottomBar(
            items = items,
            selectedIndex = { selected.coerceIn(items.indices) },
            onSelected = onSelect,
            backdrop = backdrop,
            iconContent = { item, _ ->
                Icon(ImageVector.vectorResource(item.icon), contentDescription = null, modifier = Modifier.size(24.dp))
            },
            labelContent = { item, _ -> Text(item.label, fontSize = 11.sp, maxLines = 1) },
        )
    }
}
