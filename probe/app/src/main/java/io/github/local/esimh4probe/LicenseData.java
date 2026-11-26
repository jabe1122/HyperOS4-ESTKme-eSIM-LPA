package io.github.local.esimh4probe;

import android.content.Context;
import android.util.Log;
import com.mikepenz.aboutlibraries.Libs;
import com.mikepenz.aboutlibraries.entity.Library;
import com.mikepenz.aboutlibraries.entity.License;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * What the open source licenses page lists: this project and the libraries packed into the app.
 * The AboutLibraries Gradle plugin writes the library list with their licenses into
 * {@code res/raw/aboutlibraries.json} at build time.
 */
final class LicenseData {
    private static final String TAG = "EsimH4Licenses";
    private static final String APACHE_2 = "Apache-2.0";
    private static final String APACHE_2_URL = "https://www.apache.org/licenses/LICENSE-2.0";

    /** One row of the page. */
    static final class Entry {
        String title;
        /** "1.13.0, Apache-2.0": version (when known) and license names */
        String summary;
        /** License names, comma separated. */
        String licenses;
        /** This project: full license text, or null when the build did not include it. */
        String text;
        /** This project: the license web page. A library: its own web page (what a tap opens). */
        String url;
    }

    Entry project;
    final List<Entry> libraries = new ArrayList<>();

    /** Reads and parses the generated list; call it off the main thread. */
    static LicenseData load(Context ctx) {
        LicenseData data = new LicenseData();
        Libs libs = null;
        try (InputStream in = ctx.getResources().openRawResource(R.raw.aboutlibraries)) {
            libs = new Libs.Builder().withJson(new String(in.readAllBytes(), StandardCharsets.UTF_8)).build();
        } catch (Throwable e) {
            Log.w(TAG, "Could not read the library list", e);
        }

        Entry project = new Entry();
        project.title = ctx.getString(R.string.app_name);
        project.licenses = ctx.getString(R.string.about_license_value);
        project.summary = project.licenses;
        project.url = APACHE_2_URL;
        data.project = project;
        if (libs == null) return data;

        // This project is Apache-2.0 too; take the text from the list when a library brought it in.
        for (License license : libs.getLicenses()) {
            if (APACHE_2.equalsIgnoreCase(license.getSpdxId()) && notEmpty(license.getLicenseContent())) {
                project.text = license.getLicenseContent();
                break;
            }
        }

        for (Library lib : libs.getLibraries()) {
            Entry entry = new Entry();
            entry.title = lib.getName();
            StringBuilder names = new StringBuilder();
            String licenseUrl = null;
            for (License license : lib.getLicenses()) {
                if (names.length() > 0) names.append(", ");
                names.append(notEmpty(license.getSpdxId()) ? license.getSpdxId() : license.getName());
                if (licenseUrl == null && notEmpty(license.getUrl())) licenseUrl = license.getUrl();
            }
            entry.licenses = names.toString();
            String version = lib.getArtifactVersion();
            entry.summary = notEmpty(version)
                    ? (entry.licenses.isEmpty() ? version : version + ", " + entry.licenses)
                    : entry.licenses;
            // The library's page; its source repository or license page when it has none
            if (notEmpty(lib.getWebsite())) {
                entry.url = lib.getWebsite();
            } else if (lib.getScm() != null && notEmpty(lib.getScm().getUrl())) {
                entry.url = lib.getScm().getUrl();
            } else {
                entry.url = licenseUrl;
            }
            data.libraries.add(entry);
        }
        return data;
    }

    private static boolean notEmpty(String s) {
        return s != null && !s.isBlank();
    }
}
