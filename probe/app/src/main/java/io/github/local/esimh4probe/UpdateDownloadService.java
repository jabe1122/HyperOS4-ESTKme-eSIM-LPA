package io.github.local.esimh4probe;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.SystemClock;
import android.text.format.Formatter;
import android.widget.Toast;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.security.MessageDigest;

/** Keeps update downloads and their controls alive while the UI is in the background. */
public final class UpdateDownloadService extends Service {
    private static final String CHANNEL = "update_downloads";
    private static final int ID = 4101;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Object lock = new Object();
    private volatile boolean paused;
    private volatile boolean cancelled;
    private volatile int pauseGeneration;
    private volatile HttpURLConnection connection;
    private volatile long received;
    private volatile long total = -1;
    private boolean started;
    private boolean finished;
    private String name;
    private NotificationManager notifications;

    @Override public IBinder onBind(Intent intent) { return null; }

    @Override public void onCreate() {
        super.onCreate();
        notifications = getSystemService(NotificationManager.class);
        notifications.createNotificationChannel(new NotificationChannel(CHANNEL,
                getString(R.string.update_notification_channel), NotificationManager.IMPORTANCE_LOW));
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null) { stopSelf(); return START_NOT_STICKY; }
        String action = intent.getAction();
        if (action != null) {
            if (!started || finished) { if (!started) stopSelf(); return START_NOT_STICKY; }
            synchronized (lock) {
                if ("pause".equals(action)) { paused = true; pauseGeneration++; }
                if ("resume".equals(action)) paused = false;
                if ("cancel".equals(action)) cancelled = true;
                lock.notifyAll();
            }
            if (paused || cancelled) disconnectAsync();
            if (!cancelled) notifications.notify(ID, progress());
            return START_NOT_STICKY;
        }
        if (started) return START_NOT_STICKY;
        started = true;
        name = intent.getStringExtra("name");
        startForeground(ID, progress(), ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC);
        new Thread(() -> download(intent), "EsimH4-Download").start();
        return START_NOT_STICKY;
    }

    private PendingIntent control(String action) {
        return PendingIntent.getService(this, 0,
                new Intent(this, UpdateDownloadService.class).setAction(action),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    private Notification progress() {
        String sizes = Formatter.formatShortFileSize(this, received);
        if (total > 0) sizes += " / " + Formatter.formatShortFileSize(this, total);
        return new Notification.Builder(this, CHANNEL)
                .setSmallIcon(android.R.drawable.stat_sys_download)
                .setContentTitle(getString(paused ? R.string.update_paused : R.string.update_download_started))
                .setContentText(name + " · " + sizes)
                .setContentIntent(PendingIntent.getActivity(this, 0, new Intent(this, MainActivity.class),
                        PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE))
                .setOnlyAlertOnce(true).setOngoing(true)
                .setProgress(100, total > 0 ? (int) Math.min(100, received * 100 / total) : 0, total <= 0 && !paused)
                .addAction(new Notification.Action.Builder(null,
                        getString(paused ? R.string.update_resume : R.string.update_pause),
                        control(paused ? "resume" : "pause")).build())
                .addAction(new Notification.Action.Builder(null, getString(android.R.string.cancel), control("cancel")).build())
                .build();
    }

    private void disconnectAsync() {
        HttpURLConnection current = connection;
        if (current != null) new Thread(current::disconnect, "EsimH4-Disconnect").start();
    }

    private void awaitResume() throws InterruptedException, IOException {
        synchronized (lock) {
            while (paused && !cancelled) lock.wait();
            if (cancelled) throw new IOException("Cancelled");
        }
    }

    private void download(Intent request) {
        File partial = null;
        File completed = null;
        Exception failure = null;
        try {
            String url = request.getStringExtra("url");
            String hash = request.getStringExtra("sha256");
            if (url == null || !url.startsWith("https://") || name == null
                    || !name.matches("[A-Za-z0-9._+() -]+") || hash == null
                    || !hash.matches("(?i)sha256:[0-9a-f]{64}")) {
                throw new IOException("Invalid update metadata or SHA-256 digest");
            }
            File directory = UpdateChecker.updateCache(this);
            if (!directory.isDirectory() && !directory.mkdirs()) throw new IOException("Could not create update cache");
            partial = File.createTempFile("download-", ".part", directory);
            boolean eof = false;
            long lastNotice = 0;
            while (!eof) {
                int generation = pauseGeneration;
                awaitResume();
                HttpURLConnection current = (HttpURLConnection) new URL(url).openConnection();
                connection = current;
                try {
                    current.setConnectTimeout(15000);
                    current.setReadTimeout(30000);
                    current.setRequestProperty("User-Agent", "eSIM-Probe-UpdateChecker");
                    current.setRequestProperty("Accept-Encoding", "identity");
                    if (received > 0) current.setRequestProperty("Range", "bytes=" + received + "-");
                    int status = current.getResponseCode();
                    if (status == HttpURLConnection.HTTP_PARTIAL && received > 0) {
                        String range = current.getHeaderField("Content-Range");
                        if (range == null || !range.startsWith("bytes " + received + "-")) {
                            throw new IOException("Invalid download range");
                        }
                    } else if (status == HttpURLConnection.HTTP_OK) {
                        // Servers without Range support restart safely; the final digest is always checked.
                        received = 0;
                    } else {
                        throw new IOException("Download HTTP " + status);
                    }
                    long length = current.getContentLengthLong();
                    total = length >= 0 ? received + length : -1;
                    try (InputStream in = current.getInputStream();
                         FileOutputStream out = new FileOutputStream(partial, received > 0)) {
                        byte[] buffer = new byte[65536];
                        while (!paused && !cancelled && generation == pauseGeneration) {
                            int count = in.read(buffer);
                            if (count == -1) { eof = true; break; }
                            out.write(buffer, 0, count);
                            received += count;
                            long now = SystemClock.elapsedRealtime();
                            if (now - lastNotice >= 500) {
                                lastNotice = now;
                                main.post(() -> { if (!finished && !cancelled) notifications.notify(ID, progress()); });
                            }
                        }
                    }
                } catch (IOException e) {
                    if (!paused && !cancelled && generation == pauseGeneration) throw e;
                } finally {
                    current.disconnect();
                    connection = null;
                }
            }
            awaitResume();
            if (received == 0 || (total >= 0 && received != total)) throw new IOException("Incomplete update download");
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            try (InputStream in = new FileInputStream(partial)) {
                byte[] buffer = new byte[65536];
                int count;
                while ((count = in.read(buffer)) != -1) {
                    awaitResume();
                    digest.update(buffer, 0, count);
                }
            }
            byte[] expected = new byte[32];
            for (int i = 0; i < expected.length; i++) expected[i] = (byte) Integer.parseInt(hash.substring(7 + i * 2, 9 + i * 2), 16);
            if (!MessageDigest.isEqual(expected, digest.digest())) throw new IOException("Update SHA-256 mismatch; installation blocked");
            awaitResume();
            completed = new File(directory, partial.getName() + "-" + name);
            if (!partial.renameTo(completed)) throw new IOException("Could not finish update download");
        } catch (Exception e) {
            failure = e;
        } finally {
            if (partial != null && partial.exists() && !partial.delete()) {
                android.util.Log.w("EsimH4-Updates", "Could not delete partial update");
            }
        }
        File downloaded = completed;
        Exception error = failure;
        main.post(() -> finishDownload(downloaded, request.getStringExtra("manager"), error));
    }

    private void finishDownload(File file, String manager, Exception error) {
        finished = true;
        stopForeground(STOP_FOREGROUND_REMOVE);
        try {
            if (cancelled) {
                if (file != null && !file.delete()) android.util.Log.w("EsimH4-Updates", "Could not delete cancelled update");
                Toast.makeText(this, R.string.update_cancelled, Toast.LENGTH_SHORT).show();
            } else if (error != null) {
                String message = getString(R.string.update_failed, error.getMessage());
                Toast.makeText(this, message, Toast.LENGTH_LONG).show();
                notifications.notify(ID, new Notification.Builder(this, CHANNEL)
                        .setSmallIcon(android.R.drawable.stat_notify_error).setContentTitle(getString(R.string.app_name))
                        .setContentText(message).setStyle(new Notification.BigTextStyle().bigText(message)).setAutoCancel(true).build());
            } else if (file != null) {
                if (UpdateChecker.autoInstall(this)) {
                    Intent install = UpdateChecker.installIntent(this, file, manager);
                    // A user-tapped notification can open the manager even when background launches are restricted.
                    notifications.notify(ID, new Notification.Builder(this, CHANNEL)
                            .setSmallIcon(android.R.drawable.stat_sys_download_done)
                            .setContentTitle(getString(R.string.update_ready)).setContentText(name)
                            .setContentIntent(PendingIntent.getActivity(this, 1, install,
                                    PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE))
                            .setAutoCancel(true).build());
                    startActivity(install);
                } else {
                    Toast.makeText(this, getString(R.string.update_download_saved, file.getAbsolutePath()), Toast.LENGTH_LONG).show();
                }
            }
        } catch (Exception e) {
            Toast.makeText(this, getString(R.string.update_failed, e.getMessage()), Toast.LENGTH_LONG).show();
        } finally {
            UpdateChecker.DOWNLOADING.set(false);
            stopSelf();
        }
    }

    @Override public void onTimeout(int startId, int fgsType) {
        cancelled = true;
        synchronized (lock) { lock.notifyAll(); }
        disconnectAsync();
        stopForeground(STOP_FOREGROUND_REMOVE);
        stopSelf();
    }

    @Override public void onDestroy() {
        if (!finished) {
            cancelled = true;
            synchronized (lock) { lock.notifyAll(); }
            disconnectAsync();
        }
        super.onDestroy();
    }
}
