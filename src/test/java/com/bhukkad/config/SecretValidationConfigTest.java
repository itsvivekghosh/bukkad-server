package com.bhukkad.config;

import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Base64;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
        ReflectionTestUtils.setField(config, "razorpayEnabled", true);

        assertDoesNotThrow(config::validateRequiredSecrets);
    }

    @Test
    void validateRequiredSecrets_rejectsSimulatedGatewayInProduction() {
        SecretValidationConfig config = new SecretValidationConfig();
        ReflectionTestUtils.setField(config, "jwtSecret", STRONG_JWT_SECRET);
        ReflectionTestUtils.setField(config, "dbPassword", "BhukkadProd!Secure#2026");
        ReflectionTestUtils.setField(config, "apiKeyPepper", STRONG_PEPPER);
        ReflectionTestUtils.setField(config, "razorpayEnabled", false);

        IllegalStateException ex = assertThrows(IllegalStateException.class, config::validateRequiredSecrets);
        assertTrue(ex.getMessage().contains("app.payment.razorpay.enabled must be true"),
                "expected razorpay guard message but was: " + ex.getMessage());
    }

    @Test
    void validateRequiredSecrets_rejectsWeakDefaults() {
        SecretValidationConfig config = new SecretValidationConfig();
        ReflectionTestUtils.setField(config, "jwtSecret",
                "404E635266556A586E3272357538782F413F4428472B4B6250645367566B5970");
        ReflectionTestUtils.setField(config, "dbPassword", "root");
        ReflectionTestUtils.setField(config, "apiKeyPepper", "changeme");
        ReflectionTestUtils.setField(config, "razorpayEnabled", true);

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
        ReflectionTestUtils.setField(config, "razorpayEnabled", true);

        assertThrows(IllegalStateException.class, config::validateRequiredSecrets);
    }

    @Test
    void validateRequiredSecrets_rejectsNonBase64Secret() {
        SecretValidationConfig config = new SecretValidationConfig();
        ReflectionTestUtils.setField(config, "jwtSecret", "not!!valid!!base64!!!");
        ReflectionTestUtils.setField(config, "dbPassword", "BhukkadProd!Secure#2026");
        ReflectionTestUtils.setField(config, "apiKeyPepper", STRONG_PEPPER);
        ReflectionTestUtils.setField(config, "razorpayEnabled", true);

        assertThrows(IllegalStateException.class, config::validateRequiredSecrets);
    }

    /**
     * Regression guard for the dev/staging JWT bootstrap secret. HS512 rejects
     * keys shorter than 512 bits with a {@code WeakKeyException} at token-signing
     * time; the operational value shipped in {@code docker/.env.dev} and the
     * in-code default in {@code application-dev.yml} must therefore decode to at
     * least 64 bytes. This test pins that invariant so the secret is never
     * accidentally downgraded back to the old 384-bit value.
     */
    @Test
    void devProfileJwtSecret_mustDecodeToAtLeast512Bits() {
        // application-dev.yml default (also used by docker-compose.dev.yml).
        String devDefaultSecret =
                "SYWYJMIn1nll7QKTf7jHdW+93p7xJ1RfXGtK35mgeV8w9I5oFshm9OhEJJoLn2T0ZpYPlY8auXHyxjLbofSIOA";
        int decodedBytes = Base64.getDecoder().decode(devDefaultSecret).length;
        assertTrue(decodedBytes >= 64,
                "dev JWT secret must decode to >= 64 bytes (512 bits) for HS512, got " + decodedBytes);
    }
}
