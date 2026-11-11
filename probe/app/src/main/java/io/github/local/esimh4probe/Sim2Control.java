package io.github.local.esimh4probe;

import android.content.Context;
import android.os.Process;
import android.telephony.SubscriptionInfo;
import android.telephony.TelephonyManager;

/**
 * Root-only CLI tool invoked by module scripts via app_process.
 * Probes physical Slot 2 (SIM2) eUICC identity and reads/restores UICC application state,
 * including safe handling of empty or all-profiles-disabled removable eUICC cards.
 */
public final class Sim2Control {
    private Sim2Control() {}

    public static void main(String[] args) throws Exception {
        if (Process.myUid() != 0) {
            throw new SecurityException("Root privileges required");
        }
        Context ctx = EuiccCardReader.createSystemContext();
        String cardHash;
        try {
            cardHash = EuiccCardReader.identity(ctx);
        } catch (Throwable e) {
            // When restoring after module disable/shutdown, allow matching against the previously
            // verified ICCID + EID digest so an ordinary replacement SIM is never touched.
            // Never fall back if the live probe explicitly classified SIM2 as an ordinary SIM.
            boolean confirmedNormal = "eUICC probe is normal".equals(e.getMessage());
            String verifiedIccid = Sim2Subscription.readSecureSetting(ctx, CardTypeGuard.KEY_VERIFIED_ICCID);
            String verifiedEid = Sim2Subscription.readSecureSetting(ctx, CardTypeGuard.KEY_VERIFIED_EID);
            boolean matchedSavedCard = false;
            if (!confirmedNormal
                    && args.length == 3
                    && "set".equals(args[0])
                    && !verifiedIccid.isEmpty()
                    && !verifiedEid.isEmpty()
                    && args[2].equals(verifiedEid)) {
                String physKey = Sim2Subscription.physicalKey(ctx);
                if (!physKey.isEmpty() && EuiccCardReader.hash(physKey).equals(verifiedIccid)) {
                    matchedSavedCard = true;
                } else if (physKey.isEmpty()) {
                    // Disabling UICC applications on SIM2 (uiccApplicationsEnabled == false) can clear
                    // UiccPortInfo.getIccId(); verify physical SIM2 is still present and match the
                    // pinned disabled SubscriptionInfo's normalized ICCID digest against verifiedIccid.
                    TelephonyManager tm = ctx.getSystemService(TelephonyManager.class);
                    int simState = tm != null ? tm.getSimState(1) : TelephonyManager.SIM_STATE_UNKNOWN;
                    if (simState != TelephonyManager.SIM_STATE_ABSENT
                            && simState != TelephonyManager.SIM_STATE_UNKNOWN) {
                        SubscriptionInfo sub = Sim2Subscription.resolveOrNull(ctx);
                        String subIccid = Sim2Subscription.normalizedIccid(sub);
                        if (sub != null
                                && !Sim2Subscription.enabledSafe(sub)
                                && !subIccid.isEmpty()
                                && EuiccCardReader.hash(subIccid).equals(verifiedIccid)) {
                            matchedSavedCard = true;
                        }
                    }
                }
            }
            if (matchedSavedCard) {
                cardHash = verifiedEid;
            } else {
                System.out.println("EUICC=false");
                System.exit(2);
                return;
            }
        }

        if (args.length == 1 && "probe".equals(args[0])) {
            System.out.println("EUICC=true");
            System.out.println("CARD_KEY=" + cardHash);
            System.exit(0);
            return;
        }

        if (args.length == 3 && !args[2].equals(cardHash)) {
            System.out.println("EUICC_DIFFERENT_CARD");
            System.exit(2);
            return;
        }

        if ((args.length == 2 || args.length == 3) && "set".equals(args[0])) {
            if (!"true".equals(args[1]) && !"false".equals(args[1])) {
                throw new IllegalArgumentException("Expected true or false");
            }
            boolean target = Boolean.parseBoolean(args[1]);
            SubscriptionInfo subInfo = Sim2Subscription.resolveOrNull(ctx);
            if (subInfo == null) {
                // Verified eUICC in SIM2 has no active profile/subscription yet (empty card or all disabled);
                // no UICC application row exists to toggle, so succeed cleanly without failing service.sh/restore.
                System.out.println("SIM2=empty");
                System.exit(0);
                return;
            }
            Sim2Subscription.set(ctx, subInfo, target);
            SubscriptionInfo updated = Sim2Subscription.resolveOrNull(ctx);
            if (updated == null) {
                System.out.println("SIM2=empty");
            } else {
                System.out.println("SIM2=" + Sim2Subscription.enabled(updated));
            }
            System.exit(0);
            return;
        }

        if (args.length != 1 || !"get".equals(args[0])) {
            throw new IllegalArgumentException("Usage: probe | get | set <true|false> [cardKey]");
        }

        SubscriptionInfo subInfo = Sim2Subscription.resolveOrNull(ctx);
        if (subInfo == null) {
            System.out.println("SIM2=empty");
        } else {
            System.out.println("SIM2=" + Sim2Subscription.enabled(subInfo));
        }
        System.exit(0);
    }
}
