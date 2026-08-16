package com.bhukkad.config;

import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

class SecretValidationConfigTest {

    @Test
    void validateRequiredSecrets_acceptsStrongSecrets() {
        SecretValidationConfig config = new SecretValidationConfig();
        ReflectionTestUtils.setField(config, "jwtSecret",
                "this-is-a-very-strong-production-jwt-secret-value");
        ReflectionTestUtils.setField(config, "dbPassword", "BhukkadProd!Secure#2026");

        assertDoesNotThrow(config::validateRequiredSecrets);
    }

    @Test
    void validateRequiredSecrets_rejectsWeakDefaults() {
        SecretValidationConfig config = new SecretValidationConfig();
        ReflectionTestUtils.setField(config, "jwtSecret",
                "404E635266556A586E3272357538782F413F4428472B4B6250645367566B5970");
        ReflectionTestUtils.setField(config, "dbPassword", "root");

        assertThrows(IllegalStateException.class, config::validateRequiredSecrets);
    }
}
