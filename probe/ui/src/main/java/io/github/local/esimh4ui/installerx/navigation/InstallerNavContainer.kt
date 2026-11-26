// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 InstallerX Revived contributors
// Adapted for eSIM Probe: caller-owned routes replace InstallerX-specific pages and DI.
package io.github.local.esimh4ui.installerx.navigation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.github.local.esimh4ui.installerx.animation.predictiveback.installerNavTransition
import io.github.local.esimh4ui.installerx.preferences.PredictiveBackAnimation
import io.github.local.esimh4ui.installerx.preferences.PredictiveBackExitDirection
import top.yukonga.miuix.kmp.nav.core.NavBackStack
import top.yukonga.miuix.kmp.nav.core.NavCornerClipMode
import top.yukonga.miuix.kmp.nav.core.NavDisplay
import top.yukonga.miuix.kmp.nav.core.NavDisplayEffects
import top.yukonga.miuix.kmp.nav.core.NavEntryBuilder
import top.yukonga.miuix.kmp.theme.MiuixTheme

/** Requires real Compose destinations in one host; it cannot animate separate Activity windows. */
@Composable
fun InstallerNavContainer(
    backStack: NavBackStack,
    animation: PredictiveBackAnimation,
    exitDirection: PredictiveBackExitDirection,
    cornerRadius: Dp = 0.dp,
    content: NavEntryBuilder.() -> Unit,
) {
    val navigator = remember(backStack) { Navigator(backStack) }
    val onBack = remember(navigator) { { navigator.pop() } }
    val roundAllCorners = animation == PredictiveBackAnimation.AOSP ||
        animation == PredictiveBackAnimation.Scale || animation == PredictiveBackAnimation.Classic
    val backdropColor = MiuixTheme.colorScheme.surface
    val effects = remember(cornerRadius, roundAllCorners, backdropColor) {
        NavDisplayEffects(
            enableCornerClip = true,
            cornerClipRadius = if (roundAllCorners && cornerRadius <= 0.dp) 32.dp else cornerRadius,
            cornerClipMode = if (roundAllCorners) NavCornerClipMode.All else NavCornerClipMode.Leading,
            dimAmount = 0.5f,
            backdropColor = backdropColor,
            blockInputDuringTransition = false,
        )
    }
    val transition = remember(animation, exitDirection) { installerNavTransition(animation, exitDirection) }
    CompositionLocalProvider(LocalNavigator provides navigator) {
        NavDisplay(backStack = backStack, onBack = onBack, transition = transition, effects = effects, content = content)
    }
}
