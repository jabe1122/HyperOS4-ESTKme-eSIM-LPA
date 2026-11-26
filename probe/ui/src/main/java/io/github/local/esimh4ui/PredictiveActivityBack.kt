package io.github.local.esimh4ui

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.view.View
import android.view.animation.PathInterpolator
import androidx.activity.BackEventCompat
import androidx.activity.OnBackPressedCallback
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import kotlin.math.exp
import kotlin.math.roundToInt

/** Activity-local motion; the system remains responsible for back-to-home and cross-window previews. */
internal fun installPredictiveActivityBack(activity: ThemedActivity) {
    if (activity.isTaskRoot || ThemeConfig.predictiveBack == ThemeConfig.BACK_AOSP) return
    val callback = object : OnBackPressedCallback(true), DefaultLifecycleObserver {
        private val easing = PathInterpolator(0.1f, 0.1f, 0f, 1f)
        private var edge = BackEventCompat.EDGE_LEFT
        private var committing = false
        private var animator: ValueAnimator? = null
        private var pivotX = 0.5f
        private var pivotY = 0.5f
        private val page: View get() = activity.findViewById(android.R.id.content)

        private fun cancelAnimation() {
            animator?.removeAllListeners()
            animator?.cancel()
            animator = null
        }

        override fun handleOnBackStarted(backEvent: BackEventCompat) {
            if (committing) return
            cancelAnimation()
            edge = backEvent.swipeEdge
            pivotX = if (edge == BackEventCompat.EDGE_LEFT) 0.8f else 0.2f
            val location = IntArray(2)
            page.getLocationOnScreen(location)
            pivotY = if (page.height > 0) ((backEvent.touchY - location[1]) / page.height).coerceIn(0.1f, 0.9f) else 0.5f
            render(1f, 0f)
        }

        override fun handleOnBackProgressed(backEvent: BackEventCompat) {
            if (committing) return
            val progress = easing.getInterpolation(backEvent.progress.coerceIn(0f, 1f))
            when (ThemeConfig.predictiveBack) {
                ThemeConfig.BACK_NONE -> Unit
                ThemeConfig.BACK_SCALE -> render(1f - 0.15f * progress, 0f)
                ThemeConfig.BACK_CLASSIC -> {
                    pivotX = 0.5f
                    pivotY = 0.5f
                    render(1f - 0.1f * progress, 0f)
                    page.alpha = 1f - progress
                }
                ThemeConfig.BACK_MIUIX -> render(1f, page.width * 0.25f * progress)
            }
        }

        private fun direction(): Float = when (ThemeConfig.backExitDirection) {
            1 -> 1f
            2 -> -1f
            else -> if (edge == BackEventCompat.EDGE_LEFT) 1f else -1f
        }

        private fun render(scale: Float, translation: Float) {
            val width = page.width.toFloat()
            val height = page.height.toFloat()
            val snappedScale = if (width > 0f) (scale * width).roundToInt() / width else scale
            page.pivotX = width * pivotX
            page.pivotY = height * pivotY
            page.scaleX = snappedScale
            page.scaleY = snappedScale
            // Align the transformed edges, not just the translation, to device pixels.
            val x = width * pivotX * (1f - snappedScale)
            val y = height * pivotY * (1f - snappedScale)
            page.translationX = (translation + x).roundToInt() - x
            page.translationY = y.roundToInt() - y
        }

        private fun settle(commit: Boolean) {
            cancelAnimation()
            val fromScale = page.scaleX
            val fromX = page.translationX
            val fromAlpha = page.alpha
            val classic = ThemeConfig.predictiveBack == ThemeConfig.BACK_CLASSIC
            val toScale = if (!commit) 1f else when (ThemeConfig.predictiveBack) {
                ThemeConfig.BACK_SCALE -> 0.85f
                ThemeConfig.BACK_CLASSIC -> 0.9f
                else -> 1f
            }
            val toX = if (!commit || classic) 0f else {
                val sign = if (ThemeConfig.predictiveBack == ThemeConfig.BACK_SCALE) direction() else 1f
                sign * page.width
            }
            val toAlpha = if (commit && classic) 0f else 1f
            animator = ValueAnimator.ofFloat(0f, 1f).apply {
                duration = if (commit) 200L else 300L
                // Critical damping gives cancellation a quick response without a hard stop.
                interpolator = if (commit) PathInterpolator(0.4f, 0f, 0.2f, 1f)
                else android.animation.TimeInterpolator { t ->
                    val x = 10f * t
                    ((1f - (1f + x) * exp(-x)) / (1f - 11f * exp(-10f))).coerceIn(0f, 1f)
                }
                addUpdateListener {
                    val fraction = it.animatedValue as Float
                    render(fromScale + (toScale - fromScale) * fraction, fromX + (toX - fromX) * fraction)
                    page.alpha = fromAlpha + (toAlpha - fromAlpha) * fraction
                }
                addListener(object : AnimatorListenerAdapter() {
                    override fun onAnimationEnd(animation: Animator) {
                        if (commit) {
                            activity.finish()
                            activity.overridePendingTransition(0, 0)
                        } else render(1f, 0f)
                        animator = null
                    }
                })
                start()
            }
        }

        override fun handleOnBackCancelled() {
            if (!committing) settle(false)
        }

        override fun handleOnBackPressed() {
            if (committing) return
            committing = true
            settle(true)
        }

        override fun onDestroy(owner: LifecycleOwner) {
            cancelAnimation()
        }
    }
    // Dialog and Compose callbacks registered later retain priority.
    activity.onBackPressedDispatcher.addCallback(activity, callback)
    activity.lifecycle.addObserver(callback)
}

object PredictiveActivityBack {
    @JvmStatic fun install(activity: ThemedActivity) = installPredictiveActivityBack(activity)
}
