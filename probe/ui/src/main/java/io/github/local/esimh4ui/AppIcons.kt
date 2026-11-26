package io.github.local.esimh4ui

import androidx.compose.ui.graphics.vector.ImageVector
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Back
import top.yukonga.miuix.kmp.icon.extended.ChevronBackward
import top.yukonga.miuix.kmp.icon.glass.Back
import top.yukonga.miuix.kmp.icon.glass.ChevronBackward
import top.yukonga.miuix.kmp.icon.glass.Edashcard

/** Select the same icon family; never substitute another symbol for a missing original. */
object AppIcons {
    private fun select(glass: ImageVector, original: ImageVector? = null): ImageVector =
        if (ThemeConfig.useGlass()) glass else original ?: glass

    val Back: ImageVector
        get() = select(MiuixIcons.Glass.Back, MiuixIcons.Back)

    val ChevronBackward: ImageVector
        get() = select(MiuixIcons.Glass.ChevronBackward, MiuixIcons.ChevronBackward)

    // This Miuix version only provides Edashcard in the Glass package.
    val Edashcard: ImageVector
        get() = select(MiuixIcons.Glass.Edashcard)
}
