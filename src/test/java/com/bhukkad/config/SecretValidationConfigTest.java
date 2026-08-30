package com.bhukkad.config;

import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Base64;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

class SecretValidationConfigTest {

    /** 64 raw bytes = 512 bits, the HS512 minimum. */
    private static final String STRONG_JWT_SECRET =
            Base64.getEncoder().encodeToString(
                    "a-production-64-byte-jwt-secret-not-a-default-value-xxxxxxxxxxxx".getBytes());

    private static final String STRONG_PEPPER =
            Base64.getEncoder().encodeToString(
                    "a-production-64-byte-pepper-not-a-default-value-xxxxxxxxxxxxxxx".getBytes());

    @Test
    void validateRequiredSecrets_acceptsStrongSecrets() {
        SecretValidationConfig config = new SecretValidationConfig();
        ReflectionTestUtils.setField(config, "jwtSecret", STRONG_JWT_SECRET);
        ReflectionTestUtils.setField(config, "dbPassword", "BhukkadProd!Secure#2026");
        ReflectionTestUtils.setField(config, "apiKeyPepper", STRONG_PEPPER);

        assertDoesNotThrow(config::validateRequiredSecrets);
    }

    @Test
    void validateRequiredSecrets_rejectsWeakDefaults() {
        SecretValidationConfig config = new SecretValidationConfig();
        ReflectionTestUtils.setField(config, "jwtSecret",
                "404E635266556A586E3272357538782F413F4428472B4B6250645367566B5970");
        ReflectionTestUtils.setField(config, "dbPassword", "root");
        ReflectionTestUtils.setField(config, "apiKeyPepper", "changeme");

        assertThrows(IllegalStateException.class, config::validateRequiredSecrets);
    }

    @Test
    void validateRequiredSecrets_rejectsSecretShorterThan512Bits() {
        SecretValidationConfig config = new SecretValidationConfig();
        // Decodes to well under 64 bytes -> below the HS512 minimum.
        ReflectionTestUtils.setField(config, "jwtSecret",
                Base64.getEncoder().encodeToString("too-short".getBytes()));
        ReflectionTestUtils.setField(config, "dbPassword", "BhukkadProd!Secure#2026");
        ReflectionTestUtils.setField(config, "apiKeyPepper", STRONG_PEPPER);

        assertThrows(IllegalStateException.class, config::validateRequiredSecrets);
    }

    @Test
    void validateRequiredSecrets_rejectsNonBase64Secret() {
        SecretValidationConfig config = new SecretValidationConfig();
        ReflectionTestUtils.setField(config, "jwtSecret", "not!!valid!!base64!!!");
        ReflectionTestUtils.setField(config, "dbPassword", "BhukkadProd!Secure#2026");
        ReflectionTestUtils.setField(config, "apiKeyPepper", STRONG_PEPPER);

        assertThrows(IllegalStateException.class, config::validateRequiredSecrets);
    }
}
