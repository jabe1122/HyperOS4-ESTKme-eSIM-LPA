package io.github.local.esimh4probe;

import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.net.Uri;
import android.os.Environment;
import android.provider.MediaStore;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/** Exports the module's available logcat buffers and a read-only diagnostic snapshot. */
final class ModuleLogExporter {
    private ModuleLogExporter() {}

    static String export(Context context) throws Exception {
        String name = "eSIM-Probe-logs-"
                + new SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(new Date()) + ".zip";
        ContentResolver resolver = context.getContentResolver();
        ContentValues values = new ContentValues();
        values.put(MediaStore.Downloads.DISPLAY_NAME, name);
        values.put(MediaStore.Downloads.MIME_TYPE, "application/zip");
        values.put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS);
        values.put(MediaStore.Downloads.IS_PENDING, 1);
        Uri uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values);
        if (uri == null) throw new IOException("Could not create Download entry");
        try {
            try (OutputStream output = resolver.openOutputStream(uri)) {
                if (output == null) throw new IOException("Could not open Download entry");
                try (ZipOutputStream zip = new ZipOutputStream(output)) {
                    zip.putNextEntry(new ZipEntry("module-logcat.txt"));
                    copyRootCommand(zip, "logcat -b all -d -v threadtime "
                            + "XiaomiEsimH4:V EsimH4Compat:V EsimH4Probe:V EsimH4Tester:V "
                            + "EsimH4About:V EsimH4Licenses:V '*:S'");
                    zip.closeEntry();
                    zip.putNextEntry(new ZipEntry("diagnostics.json"));
                    zip.write(ProbeInspector.inspect(context).getBytes(StandardCharsets.UTF_8));
                    zip.closeEntry();
                    zip.putNextEntry(new ZipEntry("root-diagnostics.txt"));
                    copyRootCommand(zip, "CLASSPATH=/data/adb/modules/XiaomiEsimLPAHyperOS4/compat.apk "
                            + "app_process /system/bin io.github.local.esimh4probe.ProbeInspector");
                    zip.closeEntry();
                }
            }
            values.clear();
            values.put(MediaStore.Downloads.IS_PENDING, 0);
            if (resolver.update(uri, values, null, null) != 1) {
                throw new IOException("Could not publish Download entry");
            }
            return Environment.DIRECTORY_DOWNLOADS + "/" + name;
        } catch (Exception e) {
            try {
                resolver.delete(uri, null, null);
            } catch (Exception cleanupError) {
                e.addSuppressed(cleanupError);
            }
            throw e;
        }
    }

    private static void copyRootCommand(OutputStream output, String command) throws Exception {
        Process process = new ProcessBuilder("su", "-c", command).redirectErrorStream(true).start();
        ScheduledExecutorService timeout = Executors.newSingleThreadScheduledExecutor();
        timeout.schedule(() -> { process.destroyForcibly(); }, 45, TimeUnit.SECONDS);
        try (java.io.InputStream input = process.getInputStream()) {
            byte[] buffer = new byte[16384];
            int count;
            while ((count = input.read(buffer)) != -1) output.write(buffer, 0, count);
            if (process.waitFor() != 0) throw new IOException("Root log collection failed or timed out");
        } finally {
            timeout.shutdownNow();
            process.destroy();
        }
    }
}
