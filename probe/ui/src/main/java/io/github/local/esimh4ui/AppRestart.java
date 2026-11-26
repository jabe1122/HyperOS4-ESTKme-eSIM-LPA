package io.github.local.esimh4ui;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;

/** Rebuilds the UI task without terminating services or the hosting process. */
public final class AppRestart {
    private AppRestart() {}

    public static void restart(Context context) {
        Intent launch = context.getPackageManager().getLaunchIntentForPackage(context.getPackageName());
        if (launch == null || launch.getComponent() == null) return;
        Intent restart = Intent.makeRestartActivityTask(launch.getComponent());
        restart.addFlags(Intent.FLAG_ACTIVITY_NO_ANIMATION);
        context.startActivity(restart);
        if (context instanceof Activity) {
            ((Activity) context).overridePendingTransition(0, 0);
        }
    }
}
