package io.github.local.esimh4probe;

import android.content.Context;
import android.os.Process;

/**
 * Root-only CLI tool invoked by module scripts via app_process.
 * Probes physical Slot 2 (SIM2) eUICC identity and reads/restores UICC application state.
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
            // When restoring after module disable/shutdown, allow matching against the
            // previously verified physical ICCID + EID digest so an ordinary replacement SIM is never touched.
            String physKey = Sim2Subscription.physicalKey(ctx);
            String verifiedIccid = Sim2Subscription.readSecureSetting(ctx, CardTypeGuard.KEY_VERIFIED_ICCID);
            String verifiedEid = Sim2Subscription.readSecureSetting(ctx, CardTypeGuard.KEY_VERIFIED_EID);
            if (args.length == 3
                    && "set".equals(args[0])
                    && !physKey.isEmpty()
                    && EuiccCardReader.hash(physKey).equals(verifiedIccid)
                    && args[2].equals(verifiedEid)) {
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
            Sim2Subscription.set(ctx, Boolean.parseBoolean(args[1]));
        } else if (args.length != 1 || !"get".equals(args[0])) {
            throw new IllegalArgumentException("Usage: probe | get | set <true|false> [cardKey]");
        }

        boolean enabled = Sim2Subscription.enabled(Sim2Subscription.resolve(ctx));
        System.out.println("SIM2=" + enabled);
        System.exit(0);
    }
}
