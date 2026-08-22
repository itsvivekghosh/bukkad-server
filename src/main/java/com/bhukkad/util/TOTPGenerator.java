package com.bhukkad.util;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.security.SecureRandom;
import java.time.Instant;

/**
 * RFC 6238 / RFC 4226 TOTP implementation using only the JDK's built-in
 * {@link javax.crypto.Mac} (HmacSHA1) — no external dependency required.
 *
 * <p>Secrets are base32-encoded (20 bytes → 32 characters) and codes are
 * 6-digit decimal strings. The default time step is 30 seconds; verification
 * tolerates ±1 window step to account for clock drift.</p>
 */
public final class TOTPGenerator {

    private static final int SECRET_BYTES = 20;
    private static final int TIME_STEP = 30;
    private static final int CODE_LENGTH = 6;
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final String BASE32 = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567";

    private TOTPGenerator() {
    }

    /**
     * Generates a random base32-encoded TOTP secret (32 characters).
     */
    public static String generateSecret() {
        byte[] bytes = new byte[SECRET_BYTES];
        RANDOM.nextBytes(bytes);
        return base32Encode(bytes);
    }

    /**
     * Generates the current TOTP code for the given secret.
     */
    public static String generateCode(String secret) {
        return generateCode(secret, timeStep());
    }

    /**
     * Generates a TOTP code for a specific time step (used for verification).
     */
    public static String generateCode(String secret, long timeStep) {
        byte[] key = base32Decode(secret);
        byte[] counter = new byte[8];
        for (int i = 7; i >= 0; i--) {
            counter[i] = (byte) (timeStep & 0xff);
            timeStep >>= 8;
        }

        try {
            Mac mac = Mac.getInstance("HmacSHA1");
            mac.init(new SecretKeySpec(key, "HmacSHA1"));
            byte[] hash = mac.doFinal(counter);
            int offset = hash[hash.length - 1] & 0xf;
            int binary = ((hash[offset] & 0x7f) << 24)
                    | ((hash[offset + 1] & 0xff) << 16)
                    | ((hash[offset + 2] & 0xff) << 8)
                    | (hash[offset + 3] & 0xff);
            int code = binary % (int) Math.pow(10, CODE_LENGTH);
            return String.format("%0" + CODE_LENGTH + "d", code);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to generate TOTP code", e);
        }
    }

    /**
     * Verifies a TOTP code against the current time, with a tolerance of
     * {@code window} steps before and after the current time step.
     *
     * @param secret  base32-encoded secret
     * @param code    6-digit code to verify
     * @param window  allowed drift steps (default 1 = current ± 1 step = 90s window)
     * @return true if the code matches any step in the window
     */
    public static boolean verify(String secret, String code, int window) {
        if (secret == null || code == null || code.length() != CODE_LENGTH) {
            return false;
        }
        long current = timeStep();
        for (int i = -window; i <= window; i++) {
            if (generateCode(secret, current + i).equals(code)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Generates an otpauth:// URI for the user to enroll via Google Authenticator
     * or any compatible TOTP app.
     */
    public static String otpauthUri(String secret, String email, String issuer) {
        return "otpauth://totp/" + uriEncode(issuer) + ":" + uriEncode(email)
                + "?secret=" + secret
                + "&issuer=" + uriEncode(issuer)
                + "&algorithm=SHA1&digits=6&period=30";
    }

    private static long timeStep() {
        return Instant.now().getEpochSecond() / TIME_STEP;
    }

    // ────── base32 ──────

    private static String base32Encode(byte[] bytes) {
        StringBuilder sb = new StringBuilder();
        int buffer = 0, bits = 0;
        for (byte b : bytes) {
            buffer = (buffer << 8) | (b & 0xff);
            bits += 8;
            while (bits >= 5) {
                sb.append(BASE32.charAt((buffer >> (bits - 5)) & 0x1f));
                bits -= 5;
            }
        }
        if (bits > 0) {
            sb.append(BASE32.charAt((buffer << (5 - bits)) & 0x1f));
        }
        // Pad to multiple of 8
        while (sb.length() % 8 != 0) {
            sb.append('=');
        }
        return sb.toString();
    }

    private static byte[] base32Decode(String encoded) {
        String cleaned = encoded.replace("=", "").toUpperCase();
        byte[] bytes = new byte[cleaned.length() * 5 / 8];
        int buffer = 0, bits = 0, idx = 0;
        for (char c : cleaned.toCharArray()) {
            int val = BASE32.indexOf(c);
            if (val < 0) continue;
            buffer = (buffer << 5) | val;
            bits += 5;
            if (bits >= 8) {
                bytes[idx++] = (byte) ((buffer >> (bits - 8)) & 0xff);
                bits -= 8;
            }
        }
        return bytes;
    }

    private static String uriEncode(String value) {
        return value.replace("@", "%40").replace(":", "%3A").replace(" ", "%20");
    }
}