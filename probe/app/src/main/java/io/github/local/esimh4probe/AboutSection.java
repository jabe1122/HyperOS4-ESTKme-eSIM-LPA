package io.github.local.esimh4probe;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.net.Uri;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;
import io.github.local.esimh4ui.UiKit;
import java.util.function.Consumer;

/**
 * Content of the About page in the Material 3 style (View based): version, repository, authors and
 * copyright. The Miuix style shows the Compose page in {@code AboutScreen.kt} instead.
 */
final class AboutSection {
    private AboutSection() {
    }

    /** Fills {@code col}; repository and authors appear once {@link AboutInfo} is read. */
    static void build(Activity activity, UiKit.Palette palette, LinearLayout col, Consumer<String> toast) {
        LinearLayout card = UiKit.createCard(activity, palette);
        LinearLayout.LayoutParams cardLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        cardLp.topMargin = UiKit.dp(activity, 8);
        TextView versionView = UiKit.addInfoLine(activity, palette, card, activity.getString(R.string.about_version), "…", true);
        TextView commitView = UiKit.addInfoLine(activity, palette, card, activity.getString(R.string.about_commit), "—", true);
        col.addView(card, cardLp);

        UiKit.NavRow checkRow = new UiKit.NavRow(
                activity, palette, R.drawable.ic_refresh_24, activity.getString(R.string.update_check),
                null, 24f, 24f, 0, () -> UpdateChecker.check(activity));
        ((LinearLayout.LayoutParams) checkRow.card.getLayoutParams()).topMargin = UiKit.dp(activity, 10);
        col.addView(checkRow.card);

        UiKit.NavRow licensesRow = new UiKit.NavRow(
                activity, palette, R.drawable.ic_code_24, activity.getString(R.string.licenses_title), null,
                24f, 24f, 0, () -> activity.startActivity(new Intent(activity, LicensesActivity.class)));
        ((LinearLayout.LayoutParams) licensesRow.card.getLayoutParams()).topMargin = UiKit.dp(activity, 10);
        col.addView(licensesRow.card);

        LinearLayout repoGroup = new LinearLayout(activity);
        repoGroup.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams rgLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        rgLp.topMargin = UiKit.dp(activity, 10);
        col.addView(repoGroup, rgLp);

        col.addView(UiKit.createSectionHeader(activity, palette, activity.getString(R.string.section_authors)));
        LinearLayout authorGroup = new LinearLayout(activity);
        authorGroup.setOrientation(LinearLayout.VERTICAL);
        col.addView(authorGroup, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        TextView copyright = new TextView(activity);
        LinearLayout.LayoutParams cLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        cLp.topMargin = UiKit.dp(activity, 20);
        cLp.bottomMargin = UiKit.dp(activity, 8);
        copyright.setGravity(Gravity.CENTER_HORIZONTAL);
        copyright.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f);
        copyright.setTextColor(palette.onSurfaceVariant);
        copyright.setOnClickListener(v -> ((AboutActivity) activity).onHeaderTap());
        col.addView(copyright, cLp);

        Thread loader = new Thread(() -> {
            AboutInfo info = AboutInfo.load(activity);
            activity.runOnUiThread(() -> {
                if (activity.isFinishing() || activity.isDestroyed()) return;
                bind(activity, palette, info, versionView, commitView, repoGroup, authorGroup, copyright, toast);
            });
        }, "EsimH4-About");
        loader.setDaemon(true);
        loader.start();
    }

    private static void bind(Activity activity, UiKit.Palette palette, AboutInfo info, TextView versionView,
            TextView commitView, LinearLayout repoGroup, LinearLayout authorGroup, TextView copyright,
            Consumer<String> toast) {
        versionView.setText(info.versionText());
        String commit = info.shortCommit();
        if (commit != null) commitView.setText(commit);

        boolean showParent = info.fork && info.parent != null;
        UiKit.NavRow repoRow = new UiKit.NavRow(
                activity, palette, R.drawable.ic_link_24, activity.getString(R.string.about_repo), info.repo,
                24f, showParent ? 6f : 24f, showParent ? 2 : 0, () -> openUrl(activity, info.repoUrl, toast));
        repoRow.setValue(activity.getString(info.fork ? R.string.about_repo_fork : R.string.about_repo_original));
        repoGroup.addView(repoRow.card);
        if (showParent) {
            UiKit.NavRow parentRow = new UiKit.NavRow(
                    activity, palette, R.drawable.ic_link_24, activity.getString(R.string.about_upstream), info.parent,
                    6f, 24f, 0, () -> openUrl(activity, info.parentUrl, toast));
            repoGroup.addView(parentRow.card);
        }

        int count = info.people.size();
        for (int i = 0; i < count; i++) {
            AboutInfo.Person person = info.people.get(i);
            boolean last = i == count - 1;
            UiKit.NavRow row = new UiKit.NavRow(
                    activity, palette, R.drawable.ic_person_24, person.name, personSummary(activity, person),
                    i == 0 ? 24f : 6f, last ? 24f : 6f, last ? 0 : 2,
                    () -> openUrl(activity, person.url, toast));
            if (person.avatar != null) row.setIconBitmap(person.avatar);
            authorGroup.addView(row.card);
        }

        copyright.setText(activity.getString(R.string.about_copyright, info.years()));
    }

    /** "@login" */
    static String personSummary(Activity activity, AboutInfo.Person person) {
        return "@" + person.login;
    }

    static void openUrl(Activity activity, String url, Consumer<String> toast) {
        try {
            activity.startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)));
        } catch (ActivityNotFoundException e) {
            toast.accept(activity.getString(R.string.snack_open_link_failed));
        }
    }
}
