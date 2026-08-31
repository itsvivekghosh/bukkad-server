package com.bhukkad.security;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ClientEncryptionKeyServiceTest {

    private final ClientEncryptionKeyService service = new ClientEncryptionKeyService();

    @BeforeEach
    void setUp() {
        // @PostConstruct is not triggered in unit tests — call init() manually
        service.init();
    }

    @Test
    void init_keyPairGenerated() {
        assertTrue(service.getPublicKeyJwk() != null && !service.getPublicKeyJwk().isEmpty());
    }

    @Test
    void publicKeyJwk_isValidJson() {
        String jwk = service.getPublicKeyJwk();
        assertTrue(jwk.contains("\"kty\":\"RSA\""));
        assertTrue(jwk.contains("\"alg\":\"RSA-OAEP-256\""));
        assertTrue(jwk.contains("\"n\":"));
        assertTrue(jwk.contains("\"e\":"));
    }

    @Test
    void keyExpiryIsInTheFuture() {
        assertTrue(service.keyExpiryEpochSecond() > System.currentTimeMillis() / 1000);
    }

    @Test
    void rotate_generatesNewKey() {
        String oldKey = service.getPublicKeyJwk();
        service.generateKeyPair();
        String newKey = service.getPublicKeyJwk();
        assertNotEquals(oldKey, newKey);
    }
}
