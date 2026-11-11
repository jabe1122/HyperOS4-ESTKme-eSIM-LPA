package io.github.local.esimh4probe;

import android.content.ContentResolver;
import android.content.Context;
import android.net.Uri;
import android.os.Bundle;
import android.os.Looper;
import android.os.Process;
import android.telephony.TelephonyManager;
import android.telephony.UiccCardInfo;
import java.io.ByteArrayOutputStream;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Core utilities for fast hex/SHA-256 operations, ISO-7816-4 APDU chaining (61XX / 6CXX),
 * cached system property queries, and dual-channel (IPC OMAPI + in-process RIL) probing
 * of the removable eUICC in physical slot 2 (SIM2).
 */
final class EuiccCardReader {
    static final String PROP_ACTIVE = "sys.xiaomi_esim_h4.active";
    static final String PROBE_METHOD = "xiaomi_esim_h4_probe";
    static final String ISDR_AID = "A0000005591010FFFFFFFF8900000100";
    static final int RIL_STATUS_NO_ERROR = 1;
    static final int RIL_STATUS_MISSING_RESOURCE = 2;
    static final int RIL_STATUS_NO_SUCH_ELEMENT = 3;
    static final int MAX_GET_RESPONSE_CHUNKS = 32;

    private static final char[] HEX_LOWER = "0123456789abcdef".toCharArray();
    private static final char[] HEX_UPPER = "0123456789ABCDEF".toCharArray();

    static final byte[] GET_EID_APDU_BF3E = fromHex("80E2910006BF3E035C015A00");
    static final byte[] GET_EID_APDU_BF2E = fromHex("80E2910006BF2E035C015A00");

    private static final Uri PROVIDER_URI = Uri.parse("content://com.miui.euicc.provider");
    private static final Pattern STATUS_PATTERN = Pattern.compile("status=([a-z]+)");
    private static final Pattern HASH_PATTERN = Pattern.compile("eid_hash=([0-9a-f]{64})");
    private static final Method SYS_PROP_GET;
    private static final Method TM_OPEN_CH;
    private static final Method TM_CLOSE_CH;
    private static final Method TM_TX_APDU;
    private static final Method RESP_GET_STATUS;
    private static final Method RESP_GET_CHANNEL;

    interface ApduTransceiver {
        byte[] transmit(byte[] command) throws Exception;
    }

    static {
        Method propGet = null;
        try {
            propGet = Class.forName("android.os.SystemProperties").getMethod("get", String.class, String.class);
        } catch (Throwable ignored) {
        }
        SYS_PROP_GET = propGet;

        Method openCh = null;
        Method closeCh = null;
        Method txApdu = null;
        Method getStatus = null;
        Method getChannel = null;
        try {
            openCh = TelephonyManager.class.getMethod("iccOpenLogicalChannelBySlot", int.class, String.class, int.class);
            closeCh = TelephonyManager.class.getMethod("iccCloseLogicalChannelBySlot", int.class, int.class);
            txApdu = TelephonyManager.class.getMethod("iccTransmitApduLogicalChannelBySlot",
                    int.class, int.class, int.class, int.class, int.class, int.class, int.class, String.class);
            Class<?> respClass = Class.forName("android.telephony.IccOpenLogicalChannelResponse");
            getStatus = respClass.getMethod("getStatus");
            getChannel = respClass.getMethod("getChannel");
        } catch (Throwable ignored) {
        }
        TM_OPEN_CH = openCh;
        TM_CLOSE_CH = closeCh;
        TM_TX_APDU = txApdu;
        RESP_GET_STATUS = getStatus;
        RESP_GET_CHANNEL = getChannel;
    }

    private EuiccCardReader() {}

    static boolean isModuleActive() {
        if (SYS_PROP_GET == null) return false;
        try {
            return "1".equals(SYS_PROP_GET.invoke(null, PROP_ACTIVE, "0"));
        } catch (Throwable ignored) {
            return false;
        }
    }

    static String identity(Context ctx) throws Exception {
        String status = "unknown";
        String eidHash = null;

        if (ctx != null) {
            try {
                ContentResolver resolver = ctx.getContentResolver();
                if (resolver != null) {
                    Bundle out = resolver.call(PROVIDER_URI, PROBE_METHOD, null, null);
                    if (out != null) {
                        status = out.getString("status", "unknown");
                        eidHash = out.getString("eid_hash");
                    }
                }
            } catch (Throwable ignored) {
                // Fall back to CLI content call or direct RIL probe below.
            }
        }

        int myUid = Process.myUid();
        if (eidHash == null && "unknown".equals(status) && myUid == 0) {
            try {
                java.lang.Process proc = new ProcessBuilder(
                        "/system/bin/content", "call",
                        "--uri", "content://com.miui.euicc.provider",
                        "--method", PROBE_METHOD
                ).start();
                if (!proc.waitFor(12, TimeUnit.SECONDS)) {
                    proc.destroyForcibly();
                } else {
                    String out = new String(proc.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
                    Matcher stateMatcher = STATUS_PATTERN.matcher(out);
                    Matcher hashMatcher = HASH_PATTERN.matcher(out);
                    status = stateMatcher.find() ? stateMatcher.group(1) : "unknown";
                    eidHash = hashMatcher.find() ? hashMatcher.group(1) : null;
                }
            } catch (Throwable ignored) {
            }
        }

        // In-process fallback for com.android.phone (uid=1001) and root CLI (uid=0) when
        // com.miui.euicc IPC is unavailable/unmounted (status="unknown") or inactive during root restore.
        boolean allowDirectFallback = eidHash == null
                && ctx != null
                && (("unknown".equals(status) && (myUid == 1001 || myUid == 0))
                || ("disabled".equals(status) && myUid == 0));
        if (allowDirectFallback) {
            try {
                String directEid = readEidViaRil(ctx);
                if (isValidEid(directEid)) {
                    eidHash = hash(directEid);
                    status = "euicc";
                }
            } catch (NoSuchElementException normalSim) {
                // Fail-closed: ISD-R AID returned STATUS_NO_SUCH_ELEMENT (3) -> definitely an ordinary SIM!
                status = "normal";
            } catch (Throwable rilErr) {
                // Only when RIL did NOT report STATUS_NO_SUCH_ELEMENT (e.g. channel busy), check whether
                // the modem already populated a valid 32-digit EID on physical slot 1's UiccCardInfo.
                String cardInfoEid = readSlot1CardInfoEid(ctx);
                if (isValidEid(cardInfoEid)) {
                    eidHash = hash(cardInfoEid);
                    status = "euicc";
                }
            }
        }

        if (!"euicc".equals(status) || eidHash == null) {
            throw new IllegalStateException("eUICC probe is " + status);
        }
        return eidHash;
    }

    static String readEidViaRil(Context ctx) throws Exception {
        if (ctx == null) {
            throw new IllegalStateException("Context unavailable for RIL probe");
        }
        if (TM_OPEN_CH == null || TM_CLOSE_CH == null || TM_TX_APDU == null
                || RESP_GET_STATUS == null || RESP_GET_CHANNEL == null) {
            throw new IllegalStateException("Telephony RIL logical channel methods unavailable");
        }
        TelephonyManager tm = ctx.getSystemService(TelephonyManager.class);
        if (tm == null) {
            throw new IllegalStateException("TelephonyManager unavailable");
        }
        int simState = tm.getSimState(1);
        if (simState == TelephonyManager.SIM_STATE_ABSENT || simState == TelephonyManager.SIM_STATE_UNKNOWN) {
            throw new IllegalStateException("SIM2 not present (simState=" + simState + ")");
        }

        Object openResp;
        try {
            openResp = TM_OPEN_CH.invoke(tm, 1, ISDR_AID, 0);
        } catch (InvocationTargetException ite) {
            Throwable cause = unwrap(ite);
            if (cause instanceof Exception) throw (Exception) cause;
            throw new RuntimeException(cause);
        }
        if (openResp == null) {
            throw new IllegalStateException("Null IccOpenLogicalChannelResponse on SIM2");
        }

        int status = (int) RESP_GET_STATUS.invoke(openResp);
        if (status == RIL_STATUS_NO_SUCH_ELEMENT) {
            throw new NoSuchElementException("ISD-R AID not found on SIM2");
        }
        int channel = (int) RESP_GET_CHANNEL.invoke(openResp);
        if (status != RIL_STATUS_NO_ERROR || channel < 0) {
            throw new IllegalStateException("RIL logical channel unavailable: status=" + status + ", ch=" + channel);
        }

        try {
            ApduTransceiver rilTx = cmd -> transmitRilApdu(tm, 1, channel, cmd);
            String eid = null;
            try {
                eid = extractEid(transceiveApdu(rilTx, GET_EID_APDU_BF3E));
            } catch (Throwable ignored) {
            }
            if (eid == null) {
                try {
                    eid = extractEid(transceiveApdu(rilTx, GET_EID_APDU_BF2E));
                } catch (Throwable ignored) {
                }
            }
            if (eid != null) {
                return eid;
            }
            throw new IllegalStateException("RIL EID TLV missing on both BF3E and BF2E");
        } finally {
            try {
                TM_CLOSE_CH.invoke(tm, 1, channel);
            } catch (Throwable ignored) {
            }
        }
    }

    static byte[] transmitRilApdu(TelephonyManager tm, int slotIndex, int channel, byte[] apdu) throws Exception {
        if (apdu == null || apdu.length < 4) {
            throw new IllegalArgumentException("APDU too short");
        }
        if (TM_TX_APDU == null) {
            throw new IllegalStateException("iccTransmitApduLogicalChannelBySlot unavailable");
        }
        int cla = apdu[0] & 0xFF;
        int ins = apdu[1] & 0xFF;
        int p1 = apdu[2] & 0xFF;
        int p2 = apdu[3] & 0xFF;
        int p3 = apdu.length > 4 ? (apdu[4] & 0xFF) : 0;
        String data = apdu.length > 5 ? toHexUpper(apdu, 5, apdu.length - 5) : "";

        boolean isCase4 = (apdu.length == 6 + p3 && p3 > 0);
        String raw = null;
        Exception firstErr = null;
        try {
            raw = invokeRilTx(tm, slotIndex, channel, cla, ins, p1, p2, p3, data);
        } catch (Exception e) {
            if (!isCase4) {
                throw e;
            }
            firstErr = e;
        }
        // If Case 4 APDU (5-byte header + Lc bytes + trailing Le) threw or was rejected with wrong length/error
        // by a strict Case-3 RIL, retry once as Case 3 (without trailing Le) so 61XX + GET RESPONSE fetches it.
        if (isCase4 && (raw == null || raw.length() < 4
                || "6700".equalsIgnoreCase(raw) || "6F00".equalsIgnoreCase(raw))) {
            String case3Data = toHexUpper(apdu, 5, p3);
            raw = invokeRilTx(tm, slotIndex, channel, cla, ins, p1, p2, p3, case3Data);
        } else if (firstErr != null) {
            throw firstErr;
        }
        if (raw == null || raw.length() < 4) {
            throw new IllegalStateException("Invalid RIL APDU response");
        }
        return fromHex(raw.trim());
    }

    static String invokeRilTx(
            TelephonyManager tm, int slotIndex, int channel,
            int cla, int ins, int p1, int p2, int p3, String data) throws Exception {
        try {
            return (String) TM_TX_APDU.invoke(tm, slotIndex, channel, cla, ins, p1, p2, p3, data);
        } catch (InvocationTargetException ite) {
            Throwable cause = unwrap(ite);
            if (cause instanceof Exception) throw (Exception) cause;
            throw new RuntimeException(cause);
        }
    }

    private static String readSlot1CardInfoEid(Context ctx) {
        try {
            TelephonyManager tm = ctx.getSystemService(TelephonyManager.class);
            if (tm == null) return null;
            int simState = tm.getSimState(1);
            if (simState == TelephonyManager.SIM_STATE_ABSENT || simState == TelephonyManager.SIM_STATE_UNKNOWN) {
                return null;
            }
            List<UiccCardInfo> cards = tm.getUiccCardsInfo();
            if (cards == null) return null;
            for (UiccCardInfo card : cards) {
                if (card == null || card.getPhysicalSlotIndex() != 1) continue;
                String eid = card.getEid();
                if (isValidEid(eid)) {
                    return eid.trim();
                }
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    /**
     * Executes an APDU command with ISO-7816-4 6CXX (wrong Le retry) and 61XX
     * (GET RESPONSE: 00 C0 00 00 XX chained up to {@link #MAX_GET_RESPONSE_CHUNKS}) handling.
     */
    static byte[] transceiveApdu(ApduTransceiver tx, byte[] command) throws Exception {
        if (command == null || command.length < 4) {
            throw new IllegalArgumentException("Invalid APDU command");
        }
        byte[] resp = tx.transmit(command);
        if (resp == null || resp.length < 2) {
            throw new IllegalStateException("Invalid APDU response");
        }
        if ((command[1] & 0xFF) == 0xC0) {
            return resp;
        }

        // ISO-7816-4 6C XX: exact Le requested by card; retry once with SW2 as Le.
        if (resp.length == 2 && (resp[0] & 0xFF) == 0x6C) {
            byte sw2 = resp[1];
            byte[] retry;
            if (command.length == 4) {
                retry = new byte[]{command[0], command[1], command[2], command[3], sw2};
            } else if (command.length == 5) {
                retry = command.clone();
                retry[4] = sw2;
            } else {
                int lc = command[4] & 0xFF;
                if (command.length == 5 + lc) {
                    retry = new byte[command.length + 1];
                    System.arraycopy(command, 0, retry, 0, command.length);
                    retry[command.length] = sw2;
                } else {
                    retry = command.clone();
                    retry[retry.length - 1] = sw2;
                }
            }
            resp = tx.transmit(retry);
            if (resp == null || resp.length < 2) {
                throw new IllegalStateException("Invalid APDU response after 6CXX retry");
            }
        }

        // ISO-7816-4 61 XX: more response bytes available; chain 00 C0 00 00 XX until final SW.
        if ((resp[resp.length - 2] & 0xFF) == 0x61) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            int chunks = 0;
            while ((resp[resp.length - 2] & 0xFF) == 0x61) {
                if (++chunks > MAX_GET_RESPONSE_CHUNKS) {
                    throw new IllegalStateException("GET RESPONSE chain exceeded " + MAX_GET_RESPONSE_CHUNKS + " chunks");
                }
                if (resp.length > 2) {
                    out.write(resp, 0, resp.length - 2);
                }
                byte le = resp[resp.length - 1];
                resp = tx.transmit(new byte[]{0x00, (byte) 0xC0, 0x00, 0x00, le});
                if (resp == null || resp.length < 2) {
                    throw new IllegalStateException("Invalid GET RESPONSE chunk");
                }
                if (resp.length == 2 && (resp[0] & 0xFF) == 0x6C) {
                    resp = tx.transmit(new byte[]{0x00, (byte) 0xC0, 0x00, 0x00, resp[1]});
                    if (resp == null || resp.length < 2) {
                        throw new IllegalStateException("Invalid GET RESPONSE 6CXX retry");
                    }
                }
            }
            out.write(resp, 0, resp.length);
            return out.toByteArray();
        }

        return resp;
    }

    static boolean isValidEid(String eid) {
        if (eid == null) return false;
        String s = eid.trim();
        if (s.length() != 32) return false;
        for (int i = 0; i < 32; i++) {
            char c = s.charAt(i);
            if (c < '0' || c > '9') return false;
        }
        return true;
    }

    static String extractEid(byte[] bytes) {
        if (bytes == null || bytes.length < 20) return null;
        if ((bytes[bytes.length - 2] & 0xFF) != 0x90 || (bytes[bytes.length - 1] & 0xFF) != 0x00) {
            return null;
        }
        for (int i = 0; i + 17 < bytes.length - 2; i++) {
            if ((bytes[i] & 0xFF) == 0x5A && (bytes[i + 1] & 0xFF) == 16) {
                byte[] eidBytes = new byte[16];
                System.arraycopy(bytes, i + 2, eidBytes, 0, 16);
                String candidate = toHexUpper(eidBytes);
                if (isValidEid(candidate)) {
                    return candidate;
                }
            }
        }
        return null;
    }

    static Throwable unwrap(Throwable t) {
        if (t == null) return null;
        while (t.getCause() != null && t.getCause() != t) {
            t = t.getCause();
        }
        return t;
    }

    static String hash(String value) throws Exception {
        byte[] digest = MessageDigest.getInstance("SHA-256")
                .digest(value.getBytes(StandardCharsets.US_ASCII));
        return toHexLower(digest);
    }

    static String toHexLower(byte[] bytes) {
        char[] out = new char[bytes.length * 2];
        for (int i = 0; i < bytes.length; i++) {
            int v = bytes[i] & 0xFF;
            out[i * 2] = HEX_LOWER[v >>> 4];
            out[i * 2 + 1] = HEX_LOWER[v & 0x0F];
        }
        return new String(out);
    }

    static String toHexUpper(byte[] bytes) {
        return toHexUpper(bytes, 0, bytes.length);
    }

    static String toHexUpper(byte[] bytes, int offset, int length) {
        char[] out = new char[length * 2];
        for (int i = 0; i < length; i++) {
            int v = bytes[offset + i] & 0xFF;
            out[i * 2] = HEX_UPPER[v >>> 4];
            out[i * 2 + 1] = HEX_UPPER[v & 0x0F];
        }
        return new String(out);
    }

    static byte[] fromHex(String hex) {
        int len = hex.length();
        if ((len & 1) != 0) {
            throw new IllegalArgumentException("Odd hex string length: " + len);
        }
        byte[] out = new byte[len >>> 1];
        for (int i = 0; i < len; i += 2) {
            int hi = Character.digit(hex.charAt(i), 16);
            int lo = Character.digit(hex.charAt(i + 1), 16);
            if (hi < 0 || lo < 0) {
                throw new IllegalArgumentException("Invalid hex character in: " + hex);
            }
            out[i >>> 1] = (byte) ((hi << 4) | lo);
        }
        return out;
    }

    static Context createSystemContext() throws Exception {
        if (Looper.getMainLooper() == null) {
            Looper.prepareMainLooper();
        }
        Class<?> atClass = Class.forName("android.app.ActivityThread");
        Object thread = atClass.getMethod("systemMain").invoke(null);
        Class<?> tsmClass = Class.forName("android.os.TelephonyServiceManager");
        Class.forName("android.telephony.TelephonyFrameworkInitializer")
                .getMethod("setTelephonyServiceManager", tsmClass)
                .invoke(null, tsmClass.getConstructor().newInstance());
        return (Context) atClass.getMethod("getSystemContext").invoke(thread);
    }
}
