package com.bhukkad.common.util;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.security.SecureRandom;
import java.time.Instant;

/**
 * RFC 6238 / RFC 4226 TOTP implementation using only the JDK's built-in
 * {@link javax.crypto.Mac} (HmacSHA256) — no external dependency required.
 *
 * <p>Secrets are base32-encoded (20 bytes → 32 characters) and codes are
 * 6-digit decimal strings. The default time step is 30 seconds; verification
 * tolerates ±1 window step to account for clock drift.</p>
 *
 * <p>Note: While RFC 6238 specifies HMAC-SHA-1 as the default for backwards
 * compatibility, this implementation uses HMAC-SHA-256 for stronger security.
 * Applications requiring strict RFC 6238 compatibility with legacy systems
 * may need to use HMAC-SHA-1 instead.</p>
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
     * Generates a TOTP code for the given secret at a specific time step.
     *
     * <p>This is the core TOTP algorithm implementation:
     * <ol>
     *   <li>Decode the base32 secret into a raw byte array</li>
     *   <li>Convert the time step to an 8-byte big-endian counter</li>
     *   <li>Compute HMAC-SHA-256(secret, counter) → 32-byte hash</li>
     *   <li>Apply Dynamic Truncation to the hash:</li>
     *     <ul>
     *       <li>Take the low-order 4 bits of the last byte as an offset</li>
     *       <li>Extract 4 bytes starting at that offset</li>
     *       <li>Apply bitmask to the first byte to get a 31-bit integer</li>
     *     </ul>
     *   <li>Take the resulting integer modulo 10^6 to get a 6-digit code</li>
     *   <li>Zero-pad to ensure exactly 6 digits</li>
     * </ol>
     * </p>
     *
     * @param secret  base32-encoded secret key (typically 32 characters)
     * @param timeStep the current time step (UNIX time divided by time-step size)
     * @return 6-digit numeric TOTP code as a string
     */
    public static String generateCode(String secret, long timeStep) {
        byte[] key = base32Decode(secret);
        byte[] counter = new byte[8];
        for (int i = 7; i >= 0; i--) {
            counter[i] = (byte) (timeStep & 0xff);
            timeStep >>= 8;
        }

        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key, "HmacSHA256"));
            byte[] hash = mac.doFinal(counter);

            // ------------------- DYNAMIC TRUNCATION (DT) ------------------
            //
            // RFC 4226 Section 5.3: Dynamic truncation to select 4 bytes from the HMAC result
            //
            // 1. Compute the offset as the low-order 4 bits of the last byte
            int offset = hash[hash.length - 1] & 0xf;

            // 2. Extract 4 bytes starting at the offset position
            // 3. Apply bitmask to the first byte to remove the most significant bit (MSB)
            //    This ensures we get a positive 31-bit integer (avoiding issues with signed vs unsigned)
            int binary =
                    ((hash[offset] & 0x7f) << 24)   // Most significant byte (7 bits)
                    | ((hash[offset + 1] & 0xff) << 16)  // Next byte
                    | ((hash[offset + 2] & 0xff) << 8)   // Next byte
                    | (hash[offset + 3] & 0xff);         // Least significant byte

            // 4. Compute HOTP value: numeric value modulo 10^digits
            int code = binary % (int) Math.pow(10, CODE_LENGTH);

            // 5. Zero-pad the result to ensure exactly CODE_LENGTH digits
            //    (e.g., if binary % 1000000 = 123, we want "000123")
            return String.format("%0" + CODE_LENGTH + "d", code);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to generate TOTP code", e);
        }
    }

    /**
     * Verifies a TOTP code against the current time with configurable time drift tolerance.
     *
     * <p>Due to potential clock skew between the server and client devices (e.g., phones),
     * we don't require an exact match for the current time step. Instead, we check if the
     * provided code matches any time step within a window around the current time.</p>
     *
     * <p>For example, with window=1 (the default):
     * <ul>
     *   <li>Check time step: current - 1 (30 seconds ago)</li>
     *   <li>Check time step: current (now)</li>
     *   <li>Check time step: current + 1 (30 seconds from now)</li>
     * </ul>
     * This gives a 90-second validation window (30s before + 30s current + 30s after)</p>
     *
     * <p>A larger window increases usability (more tolerant of clock drift) but slightly
     * decreases security (wider window for brute-force attacks). A window of 1 is generally
     * considered a good balance for most applications.</p>
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
     * Generates an otpauth:// URI for provisioning TOTP authenticator apps.
     *
     * <p>This method creates a URI that can be scanned by authenticator applications
     * (like Google Authenticator, Authy, Microsoft Authenticator, etc.) to automatically
     * configure the TOTP secret and parameters.</p>
     *
     * <p>The otpauth:// URI format is standardized and includes:
     * <ul>
     *   <li>Algorithm: SHA256 (the HMAC hash function being used)</li>
     *   <li>Secret: The base32-encoded shared secret</li>
     *   <li>Issuer: The service or application name (shown in the authenticator app)</li>
     *   <li>Account: The user's email or identifier</li>
     *   <li>Digits: 6 (the length of the generated code)</li>
     *   <li>Period: 30 (the time step in seconds)</li>
     * </ul>
     * </p>
     *
     * <p>Example format:
     * otpauth://totp/Issuer:Account?secret=SECRET&issuer=Issuer&algorithm=SHA256&digits=6&period=30
     * </p>
     */
    public static String otpauthUri(String secret, String email, String issuer) {
        return "otpauth://totp/" + uriEncode(issuer) + ":" + uriEncode(email)
                + "?secret=" + secret
                + "&issuer=" + uriEncode(issuer)
                + "&algorithm=SHA256&digits=6&period=30";
    }

    /**
     * Calculates the current time step based on the UNIX epoch.
     *
     * <p>TOTP is based on the concept of "time steps" - intervals of time
     * (default 30 seconds) during which the same OTP is valid. This method
     * computes which time step we're currently in by dividing the current
     * UNIX timestamp (in seconds) by the time step length.</p>
     *
     * <p>For example, with a 30-second time step:
     * <ul>
     *   <li>Time 0-29 seconds → step 0</li>
     *   <li>Time 30-59 seconds → step 1</li>
     *   <li>Time 60-89 seconds → step 2</li>
     *   <li>And so on...</li>
     * </ul>
     * </p>
     */
    private static long timeStep() {
        return Instant.now().getEpochSecond() / TIME_STEP;
    }

    // ──────
    // Base32 Encoding/Decoding (RFC 4648)
    // ──────
    //
    // TOTP secrets are typically encoded in Base32 for easier manual entry
    // and better resistance to transcription errors (avoids look-alike characters
    // like 0/O, 1/I/l, etc. that can cause confusion in Base64).
    //
    // This implementation follows RFC 4648 Base32 alphabet:
    //   A-Z (26 letters) + 2-7 (6 digits) = 32 characters
    //   Values 0-31 map to characters A-Z234567

    /**
     * Encodes a byte array to Base32 string (RFC 4648).
     *
     * <p>Base32 encoding works by processing the input bytes in 5-bit chunks:
     * <ol>
     *   <li>Buffer incoming bytes into an integer bit buffer</li>
     *   <li>Whenever we have at least 5 bits in the buffer:</li>
     *     <ul>
     *       <li>Extract the top 5 bits as an index into the Base32 alphabet</li>
     *       <li>Append the corresponding character to the output</li>
     *       <li>Remove those 5 bits from the buffer</li>
     *     </ul>
     *   <li>After processing all input bytes, if there are remaining bits (< 5):</li>
     *     <ul>
     *       <li>Left-shift them to form a 5-bit value (padding with zeros)</li>
     *       <li>Encode the final character</li>
     *     </ul>
     *   <li>Pad the output with '=' characters to make the length a multiple of 8</li>
     *     (this is required by RFC 4648 for Base32)</li>
     * </ol>
     * </p>
     *
     * @param bytes  input byte array to encode
     * @return Base32-encoded string
     */
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

/**
     * Decodes a Base32 string back to a byte array (RFC 4648).
     *
     * <p>Base32 decoding is the inverse of the encoding process:
     * <ol>
     *   <li>Remove any padding '=' characters and convert to uppercase</li>
     *   <li>For each character in the input:</li>
     *     <ul>
     *       <li>Look up its value in the Base32 alphabet (0-31)</li>
     *       <li>Shift the buffer left by 5 bits and add this value</li>
     *       <li>Increase the bit count by 5</li>
     *       <li>Whenever we have at least 8 bits in the buffer:</li>
     *         <ul>
     *           <li>Extract the top 8 bits as a byte</li>
     *           <li>Store it in the output array</li>
     *           <li>Remove those 8 bits from the buffer</li>
     *         </ul>
     *     </ul>
     *   <li>Any remaining bits (< 8) at the end are discarded (they should be zero due to padding)</li>
     * </ol>
     * </p>
     *
     * @param encoded  Base32-encoded string (with or without padding)
     * @return decoded byte array
     */
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

    /**
     * Performs minimal URL encoding for use in otpauth:// URIs.
     *
     * <p>The otpauth:// URI format requires certain characters to be percent-encoded:
     * <ul>
     *   <li>@ → %40 (separates issuer from account in the user:issuer format)</li>
     *   <li>: → %3A (separates scheme from path, and used in query parameters)</li>
     *   <li>Space → %20 (though spaces shouldn't normally appear in issuer/account)</li>
     * </ul>
     * </p>
     *
     * <p>Note: This is a simplified encoding that only handles the characters we know
     * need to be escaped in the otpauth context. For general-purpose URL encoding,
     * java.net.URLEncoder should be used instead.</p>
     *
     * @param value  string to encode
     * @return URL-encoded string
     */
    private static String uriEncode(String value) {
        return value.replace("@", "%40").replace(":", "%3A").replace(" ", "%20");
    }
}
