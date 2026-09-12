package com.bhukkad.common.util;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * RFC 6238 TOTP (HmacSHA256) generator: determinism, clock-drift window,
 * base32 helpers and otpauth URI encoding.
 */
class TOTPGeneratorTest {

    /** RFC 4648 example alphabet probe — 10 bytes decoded from 16 chars. */
    private static final String SECRET = "JBSWY3DPEHPK3PXP";

    @Test
    void generateSecret_is32Base32CharsAndUniquelyRandom() {
        String s1 = TOTPGenerator.generateSecret();
        String s2 = TOTPGenerator.generateSecret();
        assertThat(s1).hasSize(32).matches("[A-Z2-7]{32}");
        assertThat(s1).isNotEqualTo(s2);
        // decodes back to the documented 20 bytes
        String c1 = TOTPGenerator.generateCode(s1, 12345L);
        String c2 = TOTPGenerator.generateCode(s1, 12345L);
        assertThat(c1).isEqualTo(c2).matches("\\d{6}");
    }

    @Test
    void generateCode_isDeterministicPerTimeStep() {
        String a = TOTPGenerator.generateCode(SECRET, 1000L);
        assertThat(a).isEqualTo(TOTPGenerator.generateCode(SECRET, 1000L));
        assertThat(a).isNotEqualTo(TOTPGenerator.generateCode(SECRET, 1001L));
    }

    @Test
    void generateCode_matchesKnownHmacVector() {
        // Secret "abcdefghij" (RFC 4648: ABCDEFSI4Q=====...), verified against
        // the documented base32 encoding of the same bytes.
        byte[] key = "abcdefghij".getBytes(StandardCharsets.US_ASCII);
        String encoded = (String) invoke("base32Encode", byte[].class, key);
        assertThat(encoded).isEqualTo("MFRGGZDFMZTWQ2LK"); // 10 bytes → 16 chars, no padding
        String code = TOTPGenerator.generateCode(encoded, 0L);
        assertThat(code).hasSize(6).matches("\\d{6}");
    }

    @Test
    void currentCode_verifiesWithinDefaultWindow() {
        String secret = TOTPGenerator.generateSecret();
        String now = TOTPGenerator.generateCode(secret);
        assertThat(TOTPGenerator.verify(secret, now, 1)).isTrue();
    }

    @Test
    void verify_rejectsWrongLengthNullAndMismatch() {
        String secret = TOTPGenerator.generateSecret();
        assertThat(TOTPGenerator.verify(secret, "12345", 1)).isFalse();   // 5 digits
        assertThat(TOTPGenerator.verify(null, "123456", 1)).isFalse();
        assertThat(TOTPGenerator.verify(secret, null, 1)).isFalse();
        // a step far outside any sane window must not match
        String past = TOTPGenerator.generateCode(secret, System.currentTimeMillis() / 1000 / 30 - 3600);
        assertThat(TOTPGenerator.verify(secret, past, 2)).isFalse();
    }

    @Test
    void otpauthUri_percentEncodesIssuerAndAccount() {
        String uri = TOTPGenerator.otpauthUri("SECRET234", "user@bhukkad.com", "Bhukkad HQ:IN");
        assertThat(uri).startsWith("otpauth://totp/Bhukkad%20HQ%3AIN:user%40bhukkad.com");
        assertThat(uri).contains("?secret=SECRET234&issuer=Bhukkad%20HQ%3AIN");
        assertThat(uri).contains("&algorithm=SHA256&digits=6&period=30");
    }

    @Test
    void base32_roundTripsThroughEncodeAndDecode() {
        byte[] vector = {0x00, 0x01, (byte) 0xFF, 0x55, (byte) 0xAA, 0x10}; // 48 bits → tail remainder
        String encoded = (String) invoke("base32Encode", byte[].class, (Object) vector);
        assertThat(encoded).hasSize(16); // 10 payload chars + padding to multiple of 8 to multiple of 8
        byte[] back = (byte[]) invoke("base32Decode", String.class, encoded);
        assertThat(back).isEqualTo(vector);
    }

    @Test
    void emptyDecodedSecret_wrapsAsIllegalState() {
        assertThatThrownBy(() -> TOTPGenerator.generateCode("", 5L))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Failed to generate TOTP code");
    }

    private static Object invoke(String method, Class<?> type, Object arg) {
        try {
            Method m = TOTPGenerator.class.getDeclaredMethod(method, type);
            m.setAccessible(true);
            return m.invoke(null, arg);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
