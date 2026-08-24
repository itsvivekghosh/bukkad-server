package com.bhukkad.util;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.RepeatedTest;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for the RFC 6238 TOTP implementation (no external dependencies).
 * Verifies code generation, verification with window, and the otpauth URI
 * format.
 */
class TOTPGeneratorTest {

    @Test
    void generateSecret_returns32CharBase32String() {
        String secret = TOTPGenerator.generateSecret();
        assertNotNull(secret);
        assertEquals(32, secret.length());
        assertTrue(secret.matches("[A-Z2-7=]+"));
    }

    @Test
    void generateCode_returns6DigitString() {
        String secret = TOTPGenerator.generateSecret();
        String code = TOTPGenerator.generateCode(secret);
        assertNotNull(code);
        assertEquals(6, code.length());
        assertTrue(code.matches("\\d{6}"));
    }

    @Test
    void verify_correctCode_returnsTrue() {
        String secret = TOTPGenerator.generateSecret();
        String code = TOTPGenerator.generateCode(secret);
        assertTrue(TOTPGenerator.verify(secret, code, 1));
    }

    @Test
    void verify_wrongCode_returnsFalse() {
        String secret = TOTPGenerator.generateSecret();
        assertFalse(TOTPGenerator.verify(secret, "000000", 1));
    }

    @Test
    void verify_nullSecret_returnsFalse() {
        assertFalse(TOTPGenerator.verify(null, "123456", 1));
    }

    @Test
    void verify_nullCode_returnsFalse() {
        assertFalse(TOTPGenerator.verify("AAAA", null, 1));
    }

    @Test
    void verify_codeNotSixDigits_returnsFalse() {
        assertFalse(TOTPGenerator.verify("AAAA", "12345", 1));
    }

    @Test
    void otpauthUri_containsSecretAndIssuer() {
        String uri = TOTPGenerator.otpauthUri("JBSWY3DPEHPK3PXP", "admin@bhukkad.test", "Bhukkad");
        assertTrue(uri.startsWith("otpauth://totp/"));
        assertTrue(uri.contains("secret=JBSWY3DPEHPK3PXP"));
        assertTrue(uri.contains("issuer=Bhukkad"));
        assertTrue(uri.contains("admin%40bhukkad.test"));
    }

    @Test
    void sameSecret_generatesSameCodeForSameTimeStep() {
        String secret = "JBSWY3DPEHPK3PXP";
        long timeStep = System.currentTimeMillis() / 30000;
        String code1 = TOTPGenerator.generateCode(secret, timeStep);
        String code2 = TOTPGenerator.generateCode(secret, timeStep);
        assertEquals(code1, code2);
    }

    @Test
    void differentTimeStep_generatesDifferentCode() {
        String secret = "JBSWY3DPEHPK3PXP";
        long now = System.currentTimeMillis() / 30000;
        String code1 = TOTPGenerator.generateCode(secret, now);
        String code2 = TOTPGenerator.generateCode(secret, now + 1);
        // Codes should be different (extremely unlikely to collide)
        assertNotEquals(code1, code2);
    }
}