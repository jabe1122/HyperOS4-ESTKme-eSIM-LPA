package io.github.local.esimh4probe;

import android.app.Activity;

/** Shared hidden log export action for both About layouts. */
final class AboutLogExport {
    private final Activity activity;
    private final HiddenEntryGesture gesture = new HiddenEntryGesture();
    private boolean exporting;

    AboutLogExport(Activity activity) {
        this.activity = activity;
    }

    void onHeaderTap() {
        if (exporting) return;
        if (!gesture.tap(activity, R.string.export_taps_remaining, R.string.export_started)) return;
        exporting = true;
        android.content.Context context = activity.getApplicationContext();
        new Thread(() -> {
            String message;
            try {
                message = context.getString(R.string.export_saved, ModuleLogExporter.export(context));
            } catch (Exception e) {
                message = context.getString(R.string.export_failed, e.getMessage());
            }
            String result = message;
            activity.runOnUiThread(() -> {
                exporting = false;
                android.widget.Toast.makeText(context, result, android.widget.Toast.LENGTH_LONG).show();
            });
        }, "EsimH4-LogExport").start();
    }
}
