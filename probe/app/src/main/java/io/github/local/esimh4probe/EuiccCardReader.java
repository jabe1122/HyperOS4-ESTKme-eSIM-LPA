package io.github.local.esimh4probe;

import android.content.ContentResolver;
import android.content.Context;
import android.net.Uri;
import android.os.Bundle;
import android.os.Looper;
import android.os.Process;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Core utilities for fast hex/SHA-256 operations, cached system property queries,
 * and privileged IPC probing of the removable eUICC in physical slot 2 (SIM2).
 */
final class EuiccCardReader {
    static final String PROP_ACTIVE = "sys.xiaomi_esim_h4.active";
    static final String PROBE_METHOD = "xiaomi_esim_h4_probe";
    private static final Uri PROVIDER_URI = Uri.parse("content://com.miui.euicc.provider");
    private static final Pattern STATUS_PATTERN = Pattern.compile("status=([a-z]+)");
    private static final Pattern HASH_PATTERN = Pattern.compile("eid_hash=([0-9a-f]{64})");
    private static final char[] HEX_LOWER = "0123456789abcdef".toCharArray();
    private static final char[] HEX_UPPER = "0123456789ABCDEF".toCharArray();
    private static final Method SYS_PROP_GET;

    static {
        Method m = null;
        try {
            m = Class.forName("android.os.SystemProperties").getMethod("get", String.class, String.class);
        } catch (Throwable ignored) {
        }
        SYS_PROP_GET = m;
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
                // Fall back to CLI content call below if running as root in bare system context.
            }
        }

        if (eidHash == null && "unknown".equals(status) && Process.myUid() == 0) {
            java.lang.Process proc = new ProcessBuilder(
                    "/system/bin/content", "call",
                    "--uri", "content://com.miui.euicc.provider",
                    "--method", PROBE_METHOD
            ).start();
            if (!proc.waitFor(12, TimeUnit.SECONDS)) {
                proc.destroyForcibly();
                throw new IllegalStateException("eUICC probe timed out");
            }
            String out = new String(proc.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            Matcher stateMatcher = STATUS_PATTERN.matcher(out);
            Matcher hashMatcher = HASH_PATTERN.matcher(out);
            status = stateMatcher.find() ? stateMatcher.group(1) : "unknown";
            eidHash = hashMatcher.find() ? hashMatcher.group(1) : null;
        }

        if (!"euicc".equals(status) || eidHash == null) {
            throw new IllegalStateException("eUICC probe is " + status);
        }
        return eidHash;
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
        char[] out = new char[bytes.length * 2];
        for (int i = 0; i < bytes.length; i++) {
            int v = bytes[i] & 0xFF;
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
