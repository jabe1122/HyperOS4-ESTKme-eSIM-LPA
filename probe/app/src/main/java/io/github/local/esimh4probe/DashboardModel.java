package io.github.local.esimh4probe;

import android.content.Context;
import android.telephony.TelephonyManager;
import io.github.local.esimh4ui.StatusBanner;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.json.JSONObject;

/**
 * What the home and settings tabs show, computed once from a {@link ProbeInspector} report.
 * The Material 3 View pages ({@link MainActivity}) and the Miuix Compose pages
 * ({@code MainScreen.kt}) both read it, so the two styles always agree.
 */
public final class DashboardModel {
    /** The status banner's action button. */
    public enum BannerAction { NONE, OPEN_SETTINGS, DIAGNOSTICS }

    /** A health tile: green when ok, grey when muted (module off), red otherwise. */
    public static final class Tile {
        final String label;
        final String text;
        final boolean ok;
        final boolean muted;

        Tile(String label, String text, boolean ok, boolean muted) {
            this.label = label;
            this.text = text;
            this.ok = ok;
            this.muted = muted;
        }

        /** Text with the state dot, as the tile shows it. */
        String display() {
            return (ok ? "● " : "○ ") + text;
        }

        int colorRes() {
            if (ok) return R.color.status_ok_fg;
            return muted ? R.color.status_off_fg : R.color.status_err_fg;
        }
    }

    /** A settings switch row. */
    public static final class Row {
        final boolean checked;
        final boolean enabled;
        final String summary;

        Row(boolean checked, boolean enabled, String summary) {
            this.checked = checked;
            this.enabled = enabled;
            this.summary = summary;
        }
    }

    // Status banner
    final boolean bannerOk;
    final String bannerTitle;
    /** Error hint (banner not ok) or version line (banner ok). */
    final String bannerText;
    final List<StatusBanner.Tag> bannerTags;
    final BannerAction bannerAction;
    final String bannerActionText;

    /** Alert under the banner, or null when hidden. */
    final String alert;

    // SIM2 card
    final String sim2Meta;
    final int badgeRes;
    final int badgeBgRes;
    final int badgeFgRes;
    final Tile tileMount;
    final Tile tilePhoneHook;
    final Tile tileLpaHook;
    final Tile tileEuicc;
    final boolean manageEnabled;

    // Settings switches
    final Row master;
    final Row systemEsim;
    final Row carrierBridge;
    final Row lpaUriBridge;
    final Row rewriteText;
    final Row umountGuard;
    final Row estkSe1;

    DashboardModel(Context ctx, JSONObject r) {
        boolean moduleActive = r.optBoolean("moduleActive", false);
        boolean lpaPrivMounted = r.optBoolean("lpaPrivMounted", false);
        boolean lpaUpdatedSys = r.optBoolean("lpaUpdatedSystemApp", false);
        boolean phoneHook = r.optBoolean("phoneHookActive", false);
        boolean lpaHook = r.optBoolean("lpaHookActive", false);
        boolean hasFeature = r.optBoolean("feature", false);
        boolean euiccEnabled = r.optBoolean("enabled", false);

        int cardType = r.optInt("cardType", -1);
        int esimSwitch = r.optInt("esimSwitch", -1);
        int sim2State = r.optInt("sim2SimState", TelephonyManager.SIM_STATE_UNKNOWN);
        boolean hasActiveSub = r.optBoolean("sim2HasActiveSub", false);
        String carrierName = r.optString("sim2CarrierName", "");
        String lpaVer = r.has("lpaVersion") ? r.optString("lpaVersion") : ctx.getString(R.string.lpa_not_installed);

        boolean carrierBridgeOn = r.optBoolean("carrierBridgeEnabled", true);
        boolean lpaUriBridgeOn = r.optBoolean("lpaUriBridgeEnabled", true);
        boolean rewriteTextOn = r.optBoolean("rewriteTextEnabled", true);
        boolean se1 = "se1".equals(EuiccCardReader.isdrSePref());

        // 1. Status banner (SukiSU style: green card when working, plain card with a red hint
        //    and an action otherwise)
        if (!moduleActive) {
            bannerOk = false;
            bannerText = ctx.getString(R.string.status_off_hint);
            bannerTitle = ctx.getString(R.string.status_off);
            bannerAction = BannerAction.OPEN_SETTINGS;
            bannerActionText = ctx.getString(R.string.btn_turn_on);
            bannerTags = Collections.emptyList();
        } else if (!lpaPrivMounted || !phoneHook) {
            bannerOk = false;
            bannerText = ctx.getString(!lpaPrivMounted ? R.string.status_wait_mount : R.string.status_wait_lsposed);
            bannerTitle = ctx.getString(R.string.status_attention);
            bannerAction = BannerAction.NONE;
            bannerActionText = null;
            bannerTags = Collections.emptyList();
        } else {
            bannerOk = true;
            bannerTitle = ctx.getString(R.string.status_running);
            bannerText = ctx.getString(R.string.status_version_lpa, ProbeInspector.MODULE_VERSION, lpaVer);
            bannerAction = BannerAction.NONE;
            bannerActionText = null;
            List<StatusBanner.Tag> tags = new ArrayList<>();
            tags.add(new StatusBanner.Tag(ctx.getString(cardType == 1 ? R.string.tag_esim : R.string.tag_no_esim),
                    cardType == 1));
            if (se1) tags.add(new StatusBanner.Tag(ctx.getString(R.string.tag_se1), false));
            if (lpaUpdatedSys) tags.add(new StatusBanner.Tag(ctx.getString(R.string.tag_umount), false));
            bannerTags = tags;
        }

        // 2. Alert banner
        if (moduleActive && !lpaPrivMounted) {
            alert = ctx.getString(R.string.alert_mount);
        } else if (moduleActive && !phoneHook) {
            alert = ctx.getString(R.string.alert_hook, ctx.getString(R.string.app_name));
        } else {
            alert = null;
        }

        // 3. SIM2 card
        sim2Meta = ctx.getString(R.string.sim2_carrier,
                carrierName == null || carrierName.isEmpty() ? "—" : carrierName);
        if (!moduleActive) {
            badgeRes = R.string.badge_disabled;
            badgeBgRes = R.color.status_off_bg;
            badgeFgRes = R.color.status_off_fg;
        } else if (cardType == 1) {
            boolean profileReady = hasActiveSub
                    || sim2State == TelephonyManager.SIM_STATE_READY
                    || sim2State == 10;
            if (profileReady && esimSwitch != 0) {
                badgeRes = R.string.badge_profile_on;
                badgeBgRes = R.color.status_ok_bg;
                badgeFgRes = R.color.status_ok_fg;
            } else if (esimSwitch == 0) {
                badgeRes = R.string.badge_stopped;
                badgeBgRes = R.color.status_warn_bg;
                badgeFgRes = R.color.status_warn_fg;
            } else {
                badgeRes = R.string.badge_empty;
                badgeBgRes = R.color.status_ok_bg;
                badgeFgRes = R.color.status_ok_fg;
            }
        } else if (cardType == 0) {
            boolean absent = sim2State == TelephonyManager.SIM_STATE_ABSENT;
            badgeRes = absent ? R.string.badge_no_card : R.string.badge_normal_sim;
            badgeBgRes = absent ? R.color.status_off_bg : R.color.status_warn_bg;
            badgeFgRes = absent ? R.color.status_off_fg : R.color.status_warn_fg;
        } else if (!lpaPrivMounted) {
            badgeRes = R.string.badge_wait_mount;
            badgeBgRes = R.color.status_err_bg;
            badgeFgRes = R.color.status_err_fg;
        } else {
            badgeRes = R.string.badge_detecting;
            badgeBgRes = R.color.status_off_bg;
            badgeFgRes = R.color.status_off_fg;
        }

        tileMount = new Tile(ctx.getString(R.string.tile_mount),
                ctx.getString(lpaPrivMounted ? R.string.health_active : R.string.health_not_mounted),
                lpaPrivMounted, !moduleActive);
        tilePhoneHook = new Tile(ctx.getString(R.string.tile_phone_hook),
                ctx.getString(phoneHook ? R.string.health_hooked : R.string.health_inactive),
                phoneHook, false);
        tileLpaHook = new Tile(ctx.getString(R.string.tile_lpa_hook),
                ctx.getString(lpaHook ? R.string.health_hooked
                        : (lpaPrivMounted ? R.string.health_check : R.string.health_not_mounted)),
                lpaHook, !moduleActive);
        tileEuicc = new Tile(ctx.getString(R.string.tile_euicc),
                ctx.getString(euiccEnabled ? R.string.health_enabled
                        : (hasFeature ? R.string.health_pending : R.string.health_undeclared)),
                hasFeature && euiccEnabled, !moduleActive);
        manageEnabled = moduleActive && lpaPrivMounted && cardType != 0;

        // 4. Switches
        master = new Row(moduleActive, true,
                ctx.getString(moduleActive ? R.string.row_master_on : R.string.row_master_off));

        boolean canToggleEsim = moduleActive && lpaPrivMounted && phoneHook && cardType == 1;
        String subEsim;
        if (!moduleActive) {
            subEsim = ctx.getString(R.string.esim_sub_module_off);
        } else if (cardType == 1) {
            subEsim = ctx.getString(esimSwitch == 1 ? R.string.esim_sub_on : R.string.esim_sub_off);
        } else if (cardType == 0) {
            subEsim = ctx.getString(R.string.esim_sub_isolated);
        } else {
            subEsim = ctx.getString(R.string.esim_sub_detecting);
        }
        systemEsim = new Row(cardType == 1 && esimSwitch == 1, canToggleEsim, subEsim);

        boolean canUpdatePhonePrefs = moduleActive && phoneHook;
        carrierBridge = new Row(carrierBridgeOn, canUpdatePhonePrefs,
                ctx.getString(carrierBridgeOn ? R.string.carrier_sub_on : R.string.carrier_sub_off));
        lpaUriBridge = new Row(lpaUriBridgeOn, true,
                ctx.getString(lpaUriBridgeOn ? R.string.lpa_uri_sub_on : R.string.lpa_uri_sub_off));
        rewriteText = new Row(rewriteTextOn, canUpdatePhonePrefs,
                ctx.getString(rewriteTextOn ? R.string.rewrite_sub_on : R.string.rewrite_sub_off));

        String subUmount;
        if (lpaUpdatedSys) {
            subUmount = ctx.getString(R.string.umount_sub_active);
        } else if (lpaPrivMounted) {
            subUmount = ctx.getString(R.string.umount_sub_overlay);
        } else {
            subUmount = ctx.getString(R.string.umount_sub_waiting);
        }
        umountGuard = new Row(lpaUpdatedSys, moduleActive && lpaPrivMounted, subUmount);
        estkSe1 = new Row(se1, moduleActive, ctx.getString(se1 ? R.string.se1_sub_on : R.string.se1_sub_off));
    }
}
