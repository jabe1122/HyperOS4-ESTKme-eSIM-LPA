package io.github.local.esimh4probe;

import android.content.Context;
import android.os.SystemClock;
import android.widget.Toast;

/** Shared seven-tap gesture for hidden tools in both UI styles. */
final class HiddenEntryGesture {
    private int taps;
    private long lastTap;
    private Toast toast;

    boolean tap(Context context, int remainingMessage, int openedMessage) {
        long now = SystemClock.elapsedRealtime();
        if (now - lastTap > 2000) taps = 0;
        lastTap = now;
        taps++;
        if (taps == 7) {
            taps = 0;
            show(context, context.getString(openedMessage));
            return true;
        }
        if (taps >= 3) show(context, context.getString(remainingMessage, 7 - taps));
        return false;
    }

    private void show(Context context, String message) {
        if (toast != null) toast.cancel();
        toast = Toast.makeText(context, message, Toast.LENGTH_SHORT);
        toast.show();
    }
}
