package io.github.local.esimh4probe;

import android.app.Activity;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Typeface;
import android.net.Uri;
import android.provider.Settings;
import android.telephony.TelephonyManager;
import android.telephony.euicc.DownloadableSubscription;
import android.telephony.euicc.EuiccManager;
import android.text.InputType;
import android.util.Log;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;
import io.github.local.esimh4ui.StatusBanner;
import io.github.local.esimh4ui.UiKit;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * Test tab: acts as a mock carrier app to verify EuiccManager one-click eSIM installation,
 * LPA:// URI bridging and the authorization dialog safely. Codes on {@code test.esim.local} are
 * stopped by the compat module after the user confirms, so nothing is written to the card.
 *
 * <p>The logic and state live here; {@link #build} adds the Material 3 View page, and the Miuix
 * Compose page ({@code MainScreen.kt}) reads {@link #status()} and {@link #log()} after
 * {@link Listener#onChanged}. The host activity calls {@link #start} (or {@link #build}) and
 * forwards {@link #onResume}, {@link #release} and {@link #onActivityResult} to this page.
 */
final class TesterPage {
    /** Shows or hides the Test tab; kept in the app's own preferences (no root needed). */
    static final String PREFS = "ui";
    static final String KEY_SHOW_TESTER = "show_tester_tab";

    private static final String TAG = "EsimH4Tester";
    private static final String KEY_CARD_TYPE = "xiaomi_esim_h4_card_type";
    private static final String ACTION_DOWNLOAD_CB = "io.github.local.esimh4probe.ACTION_TESTER_DOWNLOAD_CB";
    static final String DEFAULT_MOCK_CODE = "LPA:1$test.esim.local$MOCK-TEST-001";
    private static final int REQ_RESOLVE = 1001;
    private static final int REQ_ACTIVATE = 1002;

    static boolean isEnabled(Context ctx) {
        return ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_SHOW_TESTER, true);
    }

    static void setEnabled(Context ctx, boolean enabled) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY_SHOW_TESTER, enabled).apply();
    }

    /** Called on the UI thread when {@link #status()} or {@link #log()} changed. */
    interface Listener {
        void onChanged();
    }

    /** The readiness banner. */
    static final class Status {
        /** Still checking: only {@link #title} is set. */
        final boolean checking;
        final boolean ready;
        final String title;
        /** Version line when ready, otherwise the reason it is not ready. */
        final String text;
        final List<StatusBanner.Tag> tags;

        Status(boolean checking, boolean ready, String title, String text, List<StatusBanner.Tag> tags) {
            this.checking = checking;
            this.ready = ready;
            this.title = title;
            this.text = text;
            this.tags = tags;
        }
    }

    private final Activity activity;
    private final UiKit.Palette palette;
    private Listener listener;
    private Status status;
    private boolean started;
    private StatusBanner banner;
    private EditText codeInput;
    private TextView logView;
    private LinearLayout logSection;
    private boolean logVisible;
    private final HiddenEntryGesture logGesture = new HiddenEntryGesture();

    boolean isLogVisible() {
        return logVisible;
    }

    void onStatusTap() {
        if (logVisible) {
            Toast.makeText(activity, R.string.log_already_shown, Toast.LENGTH_SHORT).show();
            return;
        }
        if (logGesture.tap(activity, R.string.log_taps_remaining, R.string.log_shown)) {
            logVisible = true;
            if (logSection != null) UiKit.setAnimatedVisibility(logSection, View.VISIBLE);
            notifyChanged();
        }
    }
    private final StringBuilder logBuffer = new StringBuilder();
    private int requestSeq = 1;
    private boolean receiverRegistered;

    private final BroadcastReceiver downloadReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (intent == null || !ACTION_DOWNLOAD_CB.equals(intent.getAction())) return;
            int resultCode = getResultCode();
            String phase = intent.getStringExtra("phase");
            int detailed = intent.getIntExtra(EuiccManager.EXTRA_EMBEDDED_SUBSCRIPTION_DETAILED_CODE, 0);
            boolean mockIntercepted = intent.getBooleanExtra("io.github.local.esimh4.MOCK_INTERCEPTED", false);
            Log.i(TAG, "Callback received: phase=" + phase + ", resultCode=" + resultCode
                    + ", detailed=" + detailed + ", mock=" + mockIntercepted);

            if ("initial".equals(phase)) {
                if (resultCode == EuiccManager.EMBEDDED_SUBSCRIPTION_RESULT_RESOLVABLE_ERROR) {
                    appendLog(str(R.string.log_resolvable));
                    appendLog(str(R.string.log_resolvable_note));
                    try {
                        EuiccManager em = activity.getSystemService(EuiccManager.class);
                        Intent cb = new Intent(ACTION_DOWNLOAD_CB)
                                .setPackage(activity.getPackageName())
                                .putExtra("phase", "resolved");
                        PendingIntent pi = PendingIntent.getBroadcast(
                                activity,
                                ++requestSeq,
                                cb,
                                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_MUTABLE
                        );
                        em.startResolutionActivity(activity, REQ_RESOLVE, intent, pi);
                    } catch (Throwable t) {
                        appendLog(activity.getString(R.string.log_resolution_failed,
                                t.getClass().getSimpleName() + ": " + t.getMessage()));
                    }
                } else if (resultCode == EuiccManager.EMBEDDED_SUBSCRIPTION_RESULT_OK) {
                    appendLog(str(R.string.log_direct_ok));
                } else {
                    appendLog(activity.getString(R.string.log_request_error, resultCode, detailed));
                }
            } else {
                if (resultCode == EuiccManager.EMBEDDED_SUBSCRIPTION_RESULT_OK) {
                    if (mockIntercepted) {
                        appendLog(str(R.string.log_after_auth_ok));
                        appendLog(str(R.string.log_mock_intercepted));
                    } else {
                        appendLog(str(R.string.log_real_ok));
                    }
                } else {
                    appendLog(activity.getString(R.string.log_after_auth_end, resultCode));
                }
            }
        }
    };

    TesterPage(Activity activity, UiKit.Palette palette) {
        this.activity = activity;
        this.palette = palette;
        this.status = new Status(true, false, activity.getString(R.string.tester_state_checking), null,
                new ArrayList<>());
    }

    void setListener(Listener l) {
        this.listener = l;
    }

    Status status() {
        return status;
    }

    String log() {
        return logBuffer.toString();
    }

    /** Starts listening for download callbacks and reads the status; safe to call twice. */
    void start() {
        if (started) return;
        started = true;
        activity.registerReceiver(downloadReceiver, new IntentFilter(ACTION_DOWNLOAD_CB), Context.RECEIVER_EXPORTED);
        receiverRegistered = true;
        refreshStatus();
        appendLog(activity.getString(R.string.log_ready, ProbeInspector.MODULE_VERSION));
    }

    void hideLog() {
        logVisible = false;
        if (logSection != null) UiKit.setAnimatedVisibility(logSection, View.GONE);
        notifyChanged();
    }

    void clearLog() {
        logBuffer.setLength(0);
        logBuffer.append(str(R.string.log_cleared)).append("\n");
        if (logView != null) logView.setText(logBuffer.toString());
        notifyChanged();
    }

    void logCodeReset() {
        appendLog(activity.getString(R.string.log_reset_code, DEFAULT_MOCK_CODE));
    }

    private void notifyChanged() {
        if (listener != null) listener.onChanged();
    }

    private String str(int res) {
        return activity.getString(res);
    }

    /** Builds the Material 3 View page and calls {@link #start}. */
    View build() {
        Activity ctx = activity;
        LinearLayout col = new LinearLayout(ctx);
        col.setOrientation(LinearLayout.VERTICAL);
        boolean miuix = UiKit.isMiuix();

        // Red / green readiness banner
        banner = new StatusBanner(ctx, palette);
        banner.setOnClickListener(v -> onStatusTap());
        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        blp.topMargin = UiKit.dp(ctx, 4);
        banner.setLayoutParams(blp);
        banner.showLoading(str(R.string.tester_state_checking));
        col.addView(banner);

        // Card 1: mock carrier actions
        col.addView(UiKit.createSectionHeader(ctx, palette, str(R.string.tester_mock_title)));
        LinearLayout testCard = UiKit.createCard(ctx, palette);

        TextView tvSafety = new TextView(ctx);
        tvSafety.setText(R.string.tester_safety_note);
        tvSafety.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f);
        tvSafety.setLineSpacing(UiKit.dpf(ctx, 2f), 1.0f);
        tvSafety.setTextColor(palette.onSurfaceVariant);
        testCard.addView(tvSafety);

        codeInput = new EditText(ctx);
        LinearLayout.LayoutParams ciLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        ciLp.topMargin = UiKit.dp(ctx, 12);
        codeInput.setLayoutParams(ciLp);
        codeInput.setText(DEFAULT_MOCK_CODE);
        codeInput.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        codeInput.setSingleLine(true);
        codeInput.setTypeface(Typeface.MONOSPACE);
        codeInput.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f);
        codeInput.setTextColor(palette.onSurface);
        codeInput.setPadding(UiKit.dp(ctx, 14), UiKit.dp(ctx, 12), UiKit.dp(ctx, 14), UiKit.dp(ctx, 12));
        codeInput.setBackground(miuix
                ? UiKit.roundedRect(ctx, palette.tileBg, 12f, 0, 0)
                : UiKit.roundedRect(ctx, palette.tileBg, 14f, Math.max(1, UiKit.dp(ctx, 1)), palette.outlineVariant));
        testCard.addView(codeInput);

        addFullWidth(testCard, UiKit.createActionButton(
                ctx, palette, UiKit.ButtonStyle.TONAL, 0, str(R.string.tester_reset_code), 44, () -> {
                    codeInput.setText(DEFAULT_MOCK_CODE);
                    logCodeReset();
                }), 10);

        addFullWidth(testCard, UiKit.createActionButton(
                ctx, palette, UiKit.ButtonStyle.FILLED, R.drawable.ic_science_24,
                str(R.string.tester_test1), 48, () -> {
                    String code = codeInput.getText() != null ? codeInput.getText().toString().trim() : "";
                    if (code.isEmpty()) codeInput.setText(DEFAULT_MOCK_CODE);
                    startMockDownload(code);
                }), 12);
        addFullWidth(testCard, UiKit.createActionButton(
                ctx, palette, UiKit.ButtonStyle.TONAL, 0, str(R.string.tester_test2), 44, () ->
                        startLpaUriTest(codeInput.getText() != null ? codeInput.getText().toString() : "")), 10);
        addFullWidth(testCard, UiKit.createActionButton(
                ctx, palette, UiKit.ButtonStyle.OUTLINED, 0,
                str(R.string.tester_test3), 44, this::startStandardActivationTest), 10);
        col.addView(testCard);

        // Callback console is revealed by the shared seven-tap gesture.
        logSection = new LinearLayout(ctx);
        logSection.setOrientation(LinearLayout.VERTICAL);
        logSection.setVisibility(logVisible ? View.VISIBLE : View.GONE);
        logSection.addView(UiKit.createSectionHeader(ctx, palette, str(R.string.tester_console_title)));
        col.addView(logSection);

        LinearLayout logCard = UiKit.createCard(ctx, palette);
        logView = new TextView(ctx);
        logView.setMinimumHeight(UiKit.dp(ctx, 120));
        logView.setPadding(UiKit.dp(ctx, 14), UiKit.dp(ctx, 12), UiKit.dp(ctx, 14), UiKit.dp(ctx, 12));
        logView.setBackground(UiKit.roundedRect(ctx, palette.tileBg, UiKit.radius(16f, 12f), 0, 0));
        logView.setTypeface(Typeface.MONOSPACE);
        logView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f);
        logView.setLineSpacing(UiKit.dpf(ctx, 3f), 1.0f);
        logView.setTextColor(palette.onSurface);
        logView.setTextIsSelectable(true);
        logCard.addView(logView);
        logSection.addView(logCard);
        addFullWidth(logSection, UiKit.createActionButton(
                ctx, palette, UiKit.ButtonStyle.TONAL, 0,
                str(R.string.tester_clear_log), 44, this::clearLog), 10);
        addFullWidth(logSection, UiKit.createActionButton(
                ctx, palette, UiKit.ButtonStyle.TONAL, 0,
                str(R.string.tester_hide_log), 44, this::hideLog), 10);

        start();
        showStatus();
        logView.setText(logBuffer.toString());
        return col;
    }

    private void addFullWidth(LinearLayout parent, View child, int topMarginDp) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = UiKit.dp(activity, topMarginDp);
        parent.addView(child, lp);
    }

    void onResume() {
        refreshStatus();
    }

    /** Call from the host's {@code onDestroy}. */
    void release() {
        if (!receiverRegistered) return;
        receiverRegistered = false;
        try {
            activity.unregisterReceiver(downloadReceiver);
        } catch (Throwable ignored) {
        }
    }

    /** Returns true when the result belongs to this page. */
    boolean onActivityResult(int requestCode, int resultCode) {
        if (requestCode == REQ_RESOLVE) {
            appendLog(str(resultCode == Activity.RESULT_OK
                    ? R.string.log_auth_result_ok
                    : R.string.log_auth_result_cancel));
            return true;
        } else if (requestCode == REQ_ACTIVATE) {
            appendLog(activity.getString(R.string.log_activate_result, resultCode));
            return true;
        }
        return false;
    }

    /** Reads the readiness status again (pull to refresh calls this). */
    void refreshStatus() {
        status = readStatus();
        showStatus();
        notifyChanged();
    }

    private void showStatus() {
        if (banner == null) return;
        if (status.checking) {
            banner.showLoading(status.title);
        } else if (status.ready) {
            banner.showOk(status.title, status.text, status.tags);
        } else {
            banner.showError(status.text, status.title, null, null);
        }
    }

    private Status readStatus() {
        String notReady = str(R.string.tester_state_not_ready);
        List<StatusBanner.Tag> none = new ArrayList<>();
        try {
            boolean lpaPrivMounted = ProbeInspector.isLpaPrivMounted(activity.getPackageManager());
            EuiccManager em = activity.getSystemService(EuiccManager.class);
            boolean enabled = em != null && em.isEnabled();
            int defaultCardId = ProbeInspector.defaultEuiccCardId(activity.getSystemService(TelephonyManager.class));
            int cardType = Settings.Secure.getInt(activity.getContentResolver(), KEY_CARD_TYPE, -1);
            String version = defaultCardId >= 0
                    ? ProbeInspector.MODULE_VERSION + " · cardId " + defaultCardId
                    : ProbeInspector.MODULE_VERSION;
            if (!lpaPrivMounted) {
                return new Status(false, false, notReady, str(R.string.tester_reason_mount), none);
            } else if (!enabled) {
                return new Status(false, false, notReady, str(R.string.tester_reason_euicc), none);
            } else if (cardType != 1) {
                return new Status(false, false, notReady, str(R.string.tester_reason_card), none);
            }
            List<StatusBanner.Tag> tags = new ArrayList<>();
            tags.add(new StatusBanner.Tag(str(R.string.tag_sim2_esim), true));
            tags.add(new StatusBanner.Tag("LPA", false));
            tags.add(new StatusBanner.Tag("eUICC", false));
            return new Status(false, true, str(R.string.tester_state_ready), version, tags);
        } catch (Throwable t) {
            return new Status(false, false, notReady,
                    activity.getString(R.string.st_error, String.valueOf(t.getMessage())), none);
        }
    }

    /** Test 1: the carrier install flow; an empty code uses {@link #DEFAULT_MOCK_CODE}. */
    void startMockDownload(String code) {
        try {
            if (!ProbeInspector.isLpaPrivMounted(activity.getPackageManager())) {
                appendLog(str(R.string.log_err_mount));
                return;
            }
            EuiccManager em = activity.getSystemService(EuiccManager.class);
            if (em == null || !em.isEnabled()) {
                appendLog(str(R.string.log_err_euicc));
                return;
            }
            String raw = code != null ? code.trim() : "";
            if (raw.isEmpty()) raw = DEFAULT_MOCK_CODE;
            appendLog("----------------------------------------");
            appendLog(str(R.string.log_start_download));
            appendLog(activity.getString(R.string.log_code, raw));
            DownloadableSubscription sub = DownloadableSubscription.forActivationCode(raw);
            Intent cb = new Intent(ACTION_DOWNLOAD_CB).setPackage(activity.getPackageName()).putExtra("phase", "initial");
            PendingIntent pi = PendingIntent.getBroadcast(
                    activity,
                    ++requestSeq,
                    cb,
                    PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_MUTABLE
            );
            em.downloadSubscription(sub, true, pi);
        } catch (Throwable t) {
            appendLog(activity.getString(R.string.log_call_error, t.getClass().getSimpleName() + ": " + t.getMessage()));
        }
    }

    /** Test 2: opens the code as an LPA: link. */
    void startLpaUriTest(String code) {
        try {
            String raw = code != null ? code.trim() : DEFAULT_MOCK_CODE;
            if (!raw.regionMatches(true, 0, "LPA:", 0, 4)) {
                raw = "LPA:" + raw;
            }
            appendLog("----------------------------------------");
            appendLog(activity.getString(R.string.log_start_view, raw));
            Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse(raw));
            activity.startActivity(intent);
        } catch (Throwable t) {
            appendLog(activity.getString(R.string.log_view_failed, t.getClass().getSimpleName()));
        }
    }

    /** Test 3: the standard activation page. */
    @SuppressWarnings("deprecation")
    void startStandardActivationTest() {
        try {
            appendLog("----------------------------------------");
            appendLog(str(R.string.log_start_activation));
            Intent intent = new Intent(EuiccManager.ACTION_START_EUICC_ACTIVATION);
            activity.startActivityForResult(intent, REQ_ACTIVATE);
        } catch (Throwable t) {
            appendLog(activity.getString(R.string.log_activation_failed, t.getClass().getSimpleName()));
        }
    }

    private void appendLog(String msg) {
        String ts = new SimpleDateFormat("HH:mm:ss", Locale.ROOT).format(new Date());
        logBuffer.append("[").append(ts).append("] ").append(msg).append("\n");
        if (logView != null) {
            logView.setText(logBuffer.toString());
        }
        Log.i(TAG, msg);
        notifyChanged();
    }
}
