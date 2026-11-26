package io.github.local.esimh4probe;

import android.app.Activity;
import android.content.ClipData;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import androidx.core.content.FileProvider;
import java.io.File;
import android.os.Handler;
import android.os.Looper;
import android.widget.Toast;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.math.BigInteger;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.json.JSONArray;
import org.json.JSONObject;

/** Shared release update policy for both About layouts. */
public final class UpdateChecker {
    private static final String API = "https://api.github.com/repos/";
    private static final String[] REPOSITORIES = {
            "Lucas04-nhr/HyperOS4-ESTKme-eSIM-LPA",
            "jabe1122/HyperOS4-ESTKme-eSIM-LPA"
    };
    private static final Pattern VERSION = Pattern.compile("^[vV]?(\\d+(?:\\.\\d+)*)(?:\\+[A-Za-z0-9.-]+)?$");
    private static final AtomicBoolean BUSY = new AtomicBoolean();
    static final AtomicBoolean DOWNLOADING = new AtomicBoolean();
    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    private UpdateChecker() {}

    public static boolean isNightly(Context context) {
        return context.getSharedPreferences("updates", Context.MODE_PRIVATE).getBoolean("nightly", false);
    }

    public static void setNightly(Context context, boolean nightly) {
        context.getSharedPreferences("updates", Context.MODE_PRIVATE).edit().putBoolean("nightly", nightly).apply();
    }

    public static boolean checkAtStartup(Context context) {
        return context.getSharedPreferences("updates", Context.MODE_PRIVATE).getBoolean("startup", true);
    }

    public static void setCheckAtStartup(Context context, boolean enabled) {
        context.getSharedPreferences("updates", Context.MODE_PRIVATE).edit().putBoolean("startup", enabled).apply();
    }

    public static boolean autoInstall(Context context) {
        return context.getSharedPreferences("updates", Context.MODE_PRIVATE).getBoolean("auto_install", false);
    }

    public static void setAutoInstall(Context context, boolean enabled) {
        context.getSharedPreferences("updates", Context.MODE_PRIVATE).edit().putBoolean("auto_install", enabled).apply();
    }

    public static final class Manager {
        public final String name;
        public final String packageName;

        private Manager(String name, String packageName) {
            this.name = name;
            this.packageName = packageName;
        }
    }

    private static final Manager[] MANAGERS = {
            new Manager("KernelSU", "me.weishu.kernelsu"),
            new Manager("KernelSU Next", "com.rifsxd.ksunext"),
            new Manager("SukiSU Ultra", "com.sukisu.ultra"),
            new Manager("SukiSU Nova", "com.sukisu.nova"),
            new Manager("APatch", "me.bmax.apatch")
    };

    public static java.util.List<Manager> installedManagers(Context context) {
        java.util.List<Manager> result = new java.util.ArrayList<>();
        for (Manager manager : MANAGERS) {
            try {
                context.getPackageManager().getPackageInfo(manager.packageName, 0);
                result.add(manager);
            } catch (android.content.pm.PackageManager.NameNotFoundException ignored) {
                // Only offer known managers installed for this user.
            }
        }
        return result;
    }

    public static String selectedManager(Context context) {
        return context.getSharedPreferences("updates", Context.MODE_PRIVATE).getString("manager", "");
    }

    public static void setSelectedManager(Context context, String packageName) {
        context.getSharedPreferences("updates", Context.MODE_PRIVATE).edit().putString("manager", packageName).apply();
    }

    public static String managerLabel(Context context) {
        for (Manager manager : installedManagers(context)) {
            if (manager.packageName.equals(selectedManager(context))) return manager.name;
        }
        return context.getString(R.string.update_manager_select);
    }

    private static final AtomicBoolean STARTED = new AtomicBoolean();

    public static void checkOnLaunch(Activity activity) {
        if (checkAtStartup(activity) && STARTED.compareAndSet(false, true)) check(activity, true);
    }

    public static void check(Activity context) {
        check(context, false);
    }

    private static void check(Activity context, boolean silent) {
        Context app = context.getApplicationContext();
        if (!BUSY.compareAndSet(false, true)) {
            if (!silent) toast(app, app.getString(R.string.update_checking));
            return;
        }
        if (!silent) toast(app, app.getString(R.string.update_checking));
        boolean nightly = isNightly(app);
        new Thread(() -> {
            try {
                if (silent) clearUpdateCache(app);
                AboutInfo installed = AboutInfo.load(app);
                Download update = nightly ? nightly(installed) : stable(installed);
                if (update == null) {
                    if (!silent) toast(app, app.getString(R.string.update_current));
                } else {
                    MAIN.post(() -> {
                        if (!context.isFinishing() && !context.isDestroyed()
                                && (!silent || checkAtStartup(app)) && isNightly(app) == nightly) {
                            UpdatePrompt.show(context, app.getString(R.string.update_available, update.version),
                                    update.details, () -> download(context, update));
                        }
                    });
                }
            } catch (Exception e) {
                if (!silent) toast(app, app.getString(R.string.update_failed, e.getMessage()));
            } finally {
                BUSY.set(false);
            }
        }, "EsimH4-Updates").start();
    }

    private static Download stable(AboutInfo installed) throws Exception {
        Exception failure = null;
        for (String repository : REPOSITORIES) {
            try {
                JSONObject release = get(API + repository + "/releases/latest");
                if (release == null || release.optBoolean("draft") || release.optBoolean("prerelease")) continue;
                if (!isNewer(release.getString("tag_name"), installed)) continue;
                Download update = asset(release.getJSONArray("assets"));
                if (update != null) {
                    String tag = release.getString("tag_name");
                    Matcher coded = Pattern.compile("^(\\d+)-[vV]?(\\d+(?:\\.\\d+)*)$").matcher(tag);
                    update.version = coded.matches() ? coded.group(2) + "(" + coded.group(1) + ")" : tag;
                    update.details = release.getString("html_url");
                    return update;
                }
                throw new IOException("No compatible release asset in " + repository);
            } catch (Exception e) {
                // Try upstream when the fork is unavailable, but do not report an
                // incomplete check as up to date if neither source yields an update.
                if (failure == null) failure = e;
            }
        }
        if (failure != null) throw failure;
        return null;
    }

    static File updateCache(Context app) throws IOException {
        File cache = app.getExternalCacheDir();
        if (cache == null) throw new IOException("External cache unavailable");
        return new File(cache, "updates");
    }

    private static void clearUpdateCache(Context app) {
        // Share the download guard so startup cleanup cannot remove an active download.
        if (!DOWNLOADING.compareAndSet(false, true)) return;
        try {
            File[] files = updateCache(app).listFiles();
            if (files != null) {
                for (File file : files) {
                    if (file.isFile() && !file.delete()) {
                        android.util.Log.w("EsimH4-Updates", "Could not delete cached update: " + file.getName());
                    }
                }
            }
        } catch (IOException e) {
            android.util.Log.w("EsimH4-Updates", "Could not clean update cache", e);
        } finally {
            DOWNLOADING.set(false);
        }
    }

    private static void download(Activity activity, Download update) {
        Context app = activity.getApplicationContext();
        if (autoInstall(app) && !app.getSystemService(android.app.NotificationManager.class).areNotificationsEnabled()) {
            activity.requestPermissions(new String[]{android.Manifest.permission.POST_NOTIFICATIONS}, 4101);
            toast(app, app.getString(R.string.update_notification_required));
            return;
        }
        if (!autoInstall(app)) {
            try {
                android.app.DownloadManager manager = app.getSystemService(android.app.DownloadManager.class);
                if (manager == null) throw new IOException("Download Manager unavailable");
                manager.enqueue(new android.app.DownloadManager.Request(Uri.parse(update.url))
                        .setTitle(update.name)
                        .setMimeType("application/zip")
                        .setNotificationVisibility(android.app.DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                        .setDestinationInExternalPublicDir(android.os.Environment.DIRECTORY_DOWNLOADS, update.name));
                toast(app, app.getString(R.string.update_download_started));
            } catch (Exception e) {
                toast(app, app.getString(R.string.update_failed, e.getMessage()));
            }
            return;
        }
        String managerPackage = selectedManager(app);
        if (installedManagers(app).stream()
                .noneMatch(manager -> manager.packageName.equals(managerPackage))) {
            toast(app, app.getString(R.string.update_manager_select));
            return;
        }
        if (!DOWNLOADING.compareAndSet(false, true)) {
            toast(app, app.getString(R.string.update_download_started));
            return;
        }
        try {
            app.startForegroundService(new Intent(app, UpdateDownloadService.class)
                    .putExtra("url", update.url).putExtra("name", update.name)
                    .putExtra("sha256", update.sha256).putExtra("manager", managerPackage));
        } catch (Exception e) {
            DOWNLOADING.set(false);
            toast(app, app.getString(R.string.update_failed, e.getMessage()));
        }
    }

    static Intent installIntent(Context app, File file, String managerPackage) {
        Uri uri = FileProvider.getUriForFile(app, app.getPackageName() + ".fileprovider", file);
        Intent intent = new Intent(Intent.ACTION_VIEW)
                .setDataAndType(uri, "application/zip")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_ACTIVITY_NEW_TASK);
        intent.setClipData(ClipData.newRawUri("Update", uri));
        intent.setPackage(managerPackage);
        return intent;
    }

    private static Download nightly(AboutInfo installed) throws Exception {
        // Run numbers are shared by all branches of this workflow. Include feature
        // branch nightlies, but never mix fork and upstream run numbers.
        String repository = installed.repo;
        Pattern artifactName = Pattern.compile("^XiaomiEsimLPA-HyperOS4-v(.+)-nightly\\.(\\d+)\\.zip$");
        for (int page = 1; page <= 5; page++) {
            JSONObject response = get(API + repository
                    + "/actions/workflows/nightly.yml/runs?status=success&per_page=20&page=" + page);
            if (response == null) throw new IOException("Nightly workflow unavailable");
            JSONArray runs = response.getJSONArray("workflow_runs");
            for (int i = 0; i < runs.length(); i++) {
                JSONObject run = runs.getJSONObject(i);
                long code = 600 + run.getLong("run_number");
                JSONObject artifacts = get(API + repository + "/actions/runs/" + run.getLong("id") + "/artifacts?per_page=100");
                if (artifacts == null) continue;
                JSONArray items = artifacts.getJSONArray("artifacts");
                for (int j = 0; j < items.length(); j++) {
                    JSONObject item = items.getJSONObject(j);
                    Matcher match = artifactName.matcher(item.getString("name"));
                    if (item.optBoolean("expired") || !match.matches()
                            || Long.parseLong(match.group(2)) != code) continue;
                    if (code <= installed.versionCode) return null;
                    Download update = new Download(item.getString("name"), "https://nightly.link/"
                            + repository + "/actions/artifacts/" + item.getLong("id") + ".zip");
                    update.sha256 = item.optString("digest", "");
                    update.version = match.group(1) + "(" + code + ")";
                    update.details = run.getString("html_url");
                    return update;
                }
            }
            // An empty search is not evidence that the installed build is current.
            if (runs.length() < 20) break;
        }
        throw new IOException("No downloadable nightly found in recent workflow runs");
    }

    private static boolean isNewer(String tag, AboutInfo installed) throws IOException {
        Matcher coded = Pattern.compile("^(\\d+)-[vV]?\\d+(?:\\.\\d+)*$").matcher(tag);
        if (coded.matches()) return new BigInteger(coded.group(1))
                .compareTo(BigInteger.valueOf(installed.versionCode)) > 0;
        Matcher candidate = VERSION.matcher(tag);
        Matcher current = VERSION.matcher(installed.versionName);
        if (!candidate.matches() || !current.matches()) {
            throw new IOException("Unrecognized release version: " + tag);
        }
        String[] next = candidate.group(1).split("\\.");
        String[] previous = current.group(1).split("\\.");
        for (int i = 0; i < Math.max(next.length, previous.length); i++) {
            BigInteger a = i < next.length ? new BigInteger(next[i]) : BigInteger.ZERO;
            BigInteger b = i < previous.length ? new BigInteger(previous[i]) : BigInteger.ZERO;
            int comparison = a.compareTo(b);
            if (comparison != 0) return comparison > 0;
        }
        return false;
    }

    private static Download asset(JSONArray assets) throws Exception {
        for (int i = 0; i < assets.length(); i++) {
            JSONObject asset = assets.getJSONObject(i);
            String name = asset.getString("name");
            String url = asset.getString("browser_download_url");
            if (name.startsWith("XiaomiEsimLPA-HyperOS4-") && name.endsWith(".zip")) {
                Download update = new Download(name, url);
                update.sha256 = asset.optString("digest", "");
                return update;
            }
        }
        return null;
    }

    private static JSONObject get(String url) throws Exception {
        HttpURLConnection connection = (HttpURLConnection) new URL(url).openConnection();
        connection.setConnectTimeout(15000);
        connection.setReadTimeout(20000);
        connection.setRequestProperty("Accept", "application/vnd.github+json");
        connection.setRequestProperty("X-GitHub-Api-Version", "2022-11-28");
        connection.setRequestProperty("User-Agent", "eSIM-Probe-UpdateChecker");
        try {
            int status = connection.getResponseCode();
            // GitHub returns 404 when a repository has no published stable release.
            if (status == 404) return null;
            if (status != 200) throw new IOException("GitHub HTTP " + status
                    + ((status == 403 || status == 429) ? " (rate limit or access denied)" : ""));
            try (InputStream in = connection.getInputStream()) {
                return new JSONObject(new String(in.readAllBytes(), StandardCharsets.UTF_8));
            }
        } finally {
            connection.disconnect();
        }
    }

    private static void toast(Context context, String text) {
        MAIN.post(() -> Toast.makeText(context, text, Toast.LENGTH_LONG).show());
    }

    private static final class Download {
        final String name;
        final String url;
        String version;
        String details;
        String sha256;
        Download(String name, String url) throws IOException {
            if (!url.startsWith("https://") || !name.matches("[A-Za-z0-9._+() -]+")) {
                throw new IOException("Unsafe download URL or filename");
            }
            this.name = name;
            this.url = url;
        }
    }
}
