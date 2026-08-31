package com.bhukkad.security;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.security.interfaces.RSAPrivateKey;

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

    @Test
    void rotate_keepsPreviousKeyWithinGraceWindow() {
        service.generateKeyPair();
        RSAPrivateKey oldKey = service.getPrivateKey();
        service.generateKeyPair();

        assertTrue(service.hasPreviousKeyInGrace(),
                "previous key must remain decryptable during the grace window");
        assertEquals(oldKey, service.previousPrivateKey());
        assertNotEquals(oldKey, service.getPrivateKey());
    }

    @Test
    void redisKeyPair_loadsIdenticalKeyAcrossInstances() {
        org.springframework.data.redis.core.StringRedisTemplate redis =
                org.mockito.Mockito.mock(org.springframework.data.redis.core.StringRedisTemplate.class);
        org.springframework.data.redis.core.ValueOperations<String, String> valueOps =
                org.mockito.Mockito.mock(org.springframework.data.redis.core.ValueOperations.class);
        org.mockito.Mockito.when(redis.opsForValue()).thenReturn(valueOps);

        // Instance A generates + persists to Redis (asynchronously).
        ClientEncryptionKeyService instanceA = new ClientEncryptionKeyService(redis);
        instanceA.generateKeyPair();
        String publicA = instanceA.getPublicKeyJwk();

        // Wait (up to 5s) for the async Redis write to land instead of racing
        // the background thread, then capture the persisted payload.
        org.mockito.ArgumentCaptor<String> captor =
                org.mockito.ArgumentCaptor.forClass(String.class);
        org.mockito.Mockito.verify(valueOps, org.mockito.Mockito.timeout(5000)).set(
                org.mockito.ArgumentMatchers.eq("bhukkad:encryption:keypair"),
                captor.capture(),
                org.mockito.ArgumentMatchers.any(java.time.Duration.class));

        // Instance B (different pod) loads the SAME pair from Redis.
        org.mockito.Mockito.when(valueOps.get("bhukkad:encryption:keypair"))
                .thenReturn(captor.getValue());
        ClientEncryptionKeyService instanceB = new ClientEncryptionKeyService(redis);
        instanceB.init();

        assertEquals(publicA, instanceB.getPublicKeyJwk(),
                "multi-pod deployment must share the same encryption key pair");
    }
}
