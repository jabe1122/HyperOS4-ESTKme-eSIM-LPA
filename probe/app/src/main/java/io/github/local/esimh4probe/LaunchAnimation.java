package io.github.local.esimh4probe;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.AnimatorSet;
import android.animation.ObjectAnimator;
import android.animation.ValueAnimator;
import android.app.Activity;
import android.os.SystemClock;
import android.view.ViewTreeObserver;
import java.util.concurrent.ThreadLocalRandom;
import android.view.View;
import android.view.animation.DecelerateInterpolator;
import android.window.SplashScreenView;

/** Animates the platform launch surface, shared by cold launches and UI restarts. */
final class LaunchAnimation {
    private final long deadline = SystemClock.uptimeMillis()
            + ThreadLocalRandom.current().nextLong(500, 1001);
    private boolean ready;

    private LaunchAnimation() {}

    void markReady() {
        ready = true;
    }

    static LaunchAnimation install(Activity activity) {
        LaunchAnimation launch = new LaunchAnimation();
        View content = activity.findViewById(android.R.id.content);
        content.getViewTreeObserver().addOnPreDrawListener(new ViewTreeObserver.OnPreDrawListener() {
            @Override
            public boolean onPreDraw() {
                boolean animations = ValueAnimator.areAnimatorsEnabled();
                // Reserve the final 280 ms for the reveal, without adding it after loading.
                if (animations && (!launch.ready
                        || SystemClock.uptimeMillis() < launch.deadline - 280)) {
                    content.postInvalidateOnAnimation();
                    return false;
                }
                content.getViewTreeObserver().removeOnPreDrawListener(this);
                return true;
            }
        });
        activity.getSplashScreen().setOnExitAnimationListener(splash -> {
            if (!ValueAnimator.areAnimatorsEnabled() || activity.isFinishing()) {
                splash.remove();
                return;
            }
            long remaining = Math.max(0, launch.deadline - SystemClock.uptimeMillis());
            if (remaining == 0) {
                splash.remove();
            } else {
                animate(splash, remaining);
            }
        });
        return launch;
    }

    private static void animate(SplashScreenView splash, long duration) {
        AnimatorSet animation = new AnimatorSet();
        ObjectAnimator fade = ObjectAnimator.ofFloat(splash, View.ALPHA, 1f, 0f);
        fade.setDuration(duration);
        View icon = splash.getIconView();
        if (icon != null) {
            ObjectAnimator scaleX = ObjectAnimator.ofFloat(icon, View.SCALE_X, 0.88f, 1.06f);
            ObjectAnimator scaleY = ObjectAnimator.ofFloat(icon, View.SCALE_Y, 0.88f, 1.06f);
            scaleX.setDuration(duration);
            scaleY.setDuration(duration);
            animation.playTogether(scaleX, scaleY, fade);
        } else {
            animation.playTogether(fade);
        }
        animation.setInterpolator(new DecelerateInterpolator());
        animation.addListener(new AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(Animator animator) {
                splash.remove();
            }
        });
        splash.addOnAttachStateChangeListener(new View.OnAttachStateChangeListener() {
            @Override
            public void onViewAttachedToWindow(View view) {}

            @Override
            public void onViewDetachedFromWindow(View view) {
                animation.cancel();
            }
        });
        animation.start();
    }
}
