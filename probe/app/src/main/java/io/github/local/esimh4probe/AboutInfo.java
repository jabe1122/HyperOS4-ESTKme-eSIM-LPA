package io.github.local.esimh4probe;

import android.content.Context;
import android.content.pm.PackageInfo;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.util.Log;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * What the About page shows: version, repository, authors and the copyright years.
 *
 * <p>The nightly workflow writes {@code assets/about/about.json} and the contributors' avatars
 * (.github/scripts/about_info.sh). A build without them (a local build) falls back to the original
 * repository and the two fixed authors, without avatars.
 */
public final class AboutInfo {
    private static final String TAG = "EsimH4About";
    private static final String ASSET_DIR = "about/";
    private static final String GITHUB = "https://github.com/";
    private static final String ORIGINAL_REPO = "jabe1122/HyperOS4-ESTKme-eSIM-LPA";
    /** The original author and the fork maintainer, always listed first and in this order. */
    private static final String[] FIXED_AUTHORS = {"jabe1122", "Lucas04-nhr"};
    private static final int FIRST_YEAR = 2026;

    public static final class Person {
        public String login;
        public String name;
        public String url;
        public Bitmap avatar;
    }

    public String versionName = "—";
    public long versionCode;
    public String repo = ORIGINAL_REPO;
    public String repoUrl = GITHUB + ORIGINAL_REPO;
    public boolean fork;
    public String parent;
    public String parentUrl;
    public String commit;
    public int year = FIRST_YEAR;
    public final List<Person> people = new ArrayList<>();

    /** "0.6.1 (601)" */
    public String versionText() {
        return versionName + " (" + versionCode + ")";
    }

    /** The first 7 characters of the commit, or null for a build without the workflow data. */
    public String shortCommit() {
        if (commit == null || commit.isEmpty()) return null;
        return commit.length() > 7 ? commit.substring(0, 7) : commit;
    }

    public String commitUrl() {
        return repoUrl + "/commit/" + commit;
    }

    /** "2026" or "2026–2027" */
    public String years() {
        return year > FIRST_YEAR ? FIRST_YEAR + "–" + year : String.valueOf(FIRST_YEAR);
    }

    /** The app's version name, for the settings row (no workflow data needed). */
    public static String versionName(Context ctx) {
        try {
            return ctx.getPackageManager().getPackageInfo(ctx.getPackageName(), 0).versionName;
        } catch (Throwable e) {
            return null;
        }
    }

    /** Reads the version and the workflow data; call it off the main thread (it decodes images). */
    public static AboutInfo load(Context ctx) {
        AboutInfo info = new AboutInfo();
        try {
            PackageInfo pi = ctx.getPackageManager().getPackageInfo(ctx.getPackageName(), 0);
            info.versionName = pi.versionName;
            info.versionCode = pi.getLongVersionCode();
        } catch (Throwable e) {
            Log.w(TAG, "Could not read the version", e);
        }

        JSONObject json = null;
        try (InputStream in = ctx.getAssets().open(ASSET_DIR + "about.json")) {
            json = new JSONObject(new String(in.readAllBytes(), StandardCharsets.UTF_8));
        } catch (Throwable e) {
            Log.i(TAG, "No about.json (local build): " + e.getClass().getSimpleName());
        }
        if (json != null) {
            info.repo = json.optString("repository", info.repo);
            info.repoUrl = json.optString("url", GITHUB + info.repo);
            info.fork = json.optBoolean("fork", false);
            info.parent = json.isNull("parent") ? null : json.optString("parent", null);
            info.parentUrl = json.isNull("parentUrl") ? GITHUB + info.parent : json.optString("parentUrl");
            info.commit = json.optString("commit", null);
            info.year = Math.max(FIRST_YEAR, json.optInt("year", FIRST_YEAR));
            JSONArray arr = json.optJSONArray("contributors");
            for (int i = 0; arr != null && i < arr.length(); i++) {
                JSONObject o = arr.optJSONObject(i);
                if (o == null || o.optString("login").isEmpty()) continue;
                Person person = new Person();
                person.login = o.optString("login");
                person.name = o.optString("name", person.login);
                if (person.name.isEmpty()) person.name = person.login;
                person.url = o.optString("url", GITHUB + person.login);
                String avatar = o.optString("avatar", "");
                if (!avatar.isEmpty()) person.avatar = readBitmap(ctx, ASSET_DIR + avatar);
                info.people.add(person);
            }
        }
        if (info.people.isEmpty()) {
            for (String login : FIXED_AUTHORS) {
                Person person = new Person();
                person.login = login;
                person.name = login;
                person.url = GITHUB + login;
                info.people.add(person);
            }
        }
        return info;
    }

    private static Bitmap readBitmap(Context ctx, String path) {
        try (InputStream in = ctx.getAssets().open(path)) {
            return BitmapFactory.decodeStream(in);
        } catch (Throwable e) {
            Log.w(TAG, "Could not read " + path, e);
            return null;
        }
    }
}
