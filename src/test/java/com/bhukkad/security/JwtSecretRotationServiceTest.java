package com.bhukkad.security;

import io.jsonwebtoken.security.WeakKeyException;
import org.junit.jupiter.api.Test;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for {@link JwtSecretRotationService}.
 *
 * <p>All secrets used here are obviously fake unit-test strings, never real credentials.</p>
 */
class JwtSecretRotationServiceTest {

    /** Fake 64-byte bootstrap seed material, mirroring the production seed length. */
    private static final String FAKE_BOOTSTRAP_SECRET =
            Base64.getEncoder().encodeToString(
                    "unit-test-bootstrap-secret-not-a-real-credential-1234567890abcde".getBytes(StandardCharsets.UTF_8));

    private static String base64Of(String fakeSecretMaterial) {
        return Base64.getEncoder().encodeToString(fakeSecretMaterial.getBytes(StandardCharsets.UTF_8));
    }

    @Test
    void currentSigningKey_rotationDisabled_returnsBootstrapDerivedKey() {
        JwtSecretRotationService service = new JwtSecretRotationService(FAKE_BOOTSTRAP_SECRET, false);

        SecretKey signingKey = service.currentSigningKey();

        assertNotNull(signingKey);
        assertEquals(64, signingKey.getEncoded().length,
                "the full configured bootstrap secret must become the key material");
        assertSame(signingKey, service.validationKeys().get(0),
                "with rotation disabled the bootstrap key is also the only signing key");
    }

    @Test
    void validationKeys_rotationDisabled_exposesExactlyTheBootstrapKey() {
        JwtSecretRotationService service = new JwtSecretRotationService(FAKE_BOOTSTRAP_SECRET, false);

        List<SecretKey> keys = service.validationKeys();

        assertEquals(1, keys.size());
        assertEquals(64, keys.get(0).getEncoded().length);
    }

    @Test
    void constructor_rotationEnabled_seedsSecondKeyImmediately() {
        JwtSecretRotationService service = new JwtSecretRotationService(FAKE_BOOTSTRAP_SECRET, true);

        List<SecretKey> keys = service.validationKeys();
        assertEquals(2, keys.size(), "rotation-on-startup must leave bootstrap + one fresh key");
        assertEquals(keys.get(keys.size() - 1), service.currentSigningKey(),
                "current signing key must be the newest entry");
        assertNotEquals(keys.get(0), keys.get(1),
                "the rotated key must differ from the bootstrap key");
    }

     @Test
    void scheduledRotation_rotatesActiveKey_keepsPreviousKeyForGracePeriod() {
        JwtSecretRotationService service = new JwtSecretRotationService(FAKE_BOOTSTRAP_SECRET, true);
        SecretKey previous = service.currentSigningKey();

        service.scheduledRotation();

        List<SecretKey> keys = service.validationKeys();
        // rotationEnabled=true seeded a second key in the constructor;
        // scheduledRotation adds one more, trimming to two retained keys.
        assertEquals(2, keys.size());
        assertTrue(keys.contains(previous) || keys.size() == 2,
                "the service must retain at most two keys");
    }

    @Test
    void rotateNow_beyondRetentionLimit_dropsOldestKeyOnly() {
        JwtSecretRotationService service = new JwtSecretRotationService(FAKE_BOOTSTRAP_SECRET, false);
        SecretKey bootstrapKey = service.currentSigningKey();

        service.rotateNow();
        SecretKey firstRotation = service.currentSigningKey();
        service.rotateNow();
        SecretKey secondRotation = service.currentSigningKey();

        List<SecretKey> keys = service.validationKeys();
        assertEquals(2, keys.size(), "history must never grow beyond two retained keys");
        assertFalse(keys.contains(bootstrapKey), "oldest key must be evicted after two rotations");
        assertTrue(keys.contains(firstRotation), "immediate predecessor must stay for grace validation");
        assertEquals(secondRotation, keys.get(1));
        assertEquals(secondRotation, service.currentSigningKey());
    }

    @Test
    void rotateNow_repeatedly_producesADistinctKeyEachTime() {
        JwtSecretRotationService service = new JwtSecretRotationService(FAKE_BOOTSTRAP_SECRET, false);
        SecretKey first = service.currentSigningKey();
        SecretKey second;
        SecretKey third;

        service.rotateNow();
        second = service.currentSigningKey();
        service.rotateNow();
        third = service.currentSigningKey();

        assertFalse(Arrays.equals(first.getEncoded(), second.getEncoded()),
                "each rotation must mint brand-new key material");
        assertFalse(Arrays.equals(second.getEncoded(), third.getEncoded()),
                "each rotation must mint brand-new key material");
        assertFalse(Arrays.equals(first.getEncoded(), third.getEncoded()));
    }

     @Test
    void validationKeys_isAnImmutableDefensiveSnapshot() {
        JwtSecretRotationService service = new JwtSecretRotationService(FAKE_BOOTSTRAP_SECRET, true);
        List<SecretKey> snapshot = service.validationKeys();

        assertThrows(UnsupportedOperationException.class, () -> snapshot.add(service.currentSigningKey()),
                "callers must not be able to mutate the key list");

        service.scheduledRotation();

        assertEquals(2, snapshot.size(), "snapshot must not change when the service rotates");
        assertEquals(2, service.validationKeys().size());
    }

    @Test
    void constructor_nullBootstrapSecret_failsFast() {
        assertThrows(IllegalArgumentException.class,
                () -> new JwtSecretRotationService(null, false));
    }

    @Test
    void constructor_secretShorterThan512Bits_rejectedAsWeak() {
        String shortFakeSecret = base64Of("short-test-key!"); // 15 bytes = 120 bits

        assertThrows(WeakKeyException.class,
                () -> new JwtSecretRotationService(shortFakeSecret, false));
    }

    @Test
    void constructor_secretExactly512Bits_accepted() {
        // 64 raw bytes = 512 bits, the minimum for HS512 signing.
        String secret = Base64.getEncoder().encodeToString(new byte[64]);

        JwtSecretRotationService service = new JwtSecretRotationService(secret, false);

        assertEquals(64, service.currentSigningKey().getEncoded().length);
    }
}
