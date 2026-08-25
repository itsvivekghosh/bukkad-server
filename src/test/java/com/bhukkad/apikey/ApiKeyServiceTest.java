package com.bhukkad.apikey;

import com.bhukkad.exception.ResourceNotFoundException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ApiKeyServiceTest {

    @Mock
    private ApiKeyRepository apiKeyRepository;

    private ApiKeyService apiKeyService;

    @BeforeEach
    void setUp() {
        apiKeyService = new ApiKeyService(apiKeyRepository, "unit-test-pepper");
    }

    @Test
    void create_storesHashAndReturnsPlaintextOnce() {
        when(apiKeyRepository.save(any(ApiKey.class))).thenAnswer(inv -> inv.getArgument(0));

        ApiKeyService.CreatedApiKey created = apiKeyService.create("Partner App", 42L, "orders:read", null);

        assertNotNull(created.apiKey());
        assertTrue(created.apiKey().startsWith("bhk_"));
        assertTrue(created.apiKey().contains("_"));
        // The plaintext key must NOT equal the stored hash.
        ArgumentCaptor<ApiKey> captor = ArgumentCaptor.forClass(ApiKey.class);
        verify(apiKeyRepository).save(captor.capture());
        assertNotEquals(created.apiKey(), captor.getValue().getKeyHash());
        assertEquals(64, captor.getValue().getKeyHash().length());
    }

    @Test
    void validate_validKey_returnsRecord() {
        when(apiKeyRepository.findByKeyHash(any())).thenAnswer(inv -> {
            ApiKey key = new ApiKey();
            key.setId(1L);
            key.setKeyHash(inv.getArgument(0));
            key.setStatus(ApiKey.ApiKeyStatus.ACTIVE);
            key.setPartnerId(42L);
            return Optional.of(key);
        });
        when(apiKeyRepository.save(any(ApiKey.class))).thenAnswer(inv -> inv.getArgument(0));

        ApiKeyService.CreatedApiKey created = apiKeyService.create("x", 42L, null, null);
        Optional<ApiKey> result = apiKeyService.validate(created.apiKey());

        assertTrue(result.isPresent());
        assertEquals(42L, result.get().getPartnerId());
    }

    @Test
    void validate_nullOrInvalidPrefix_returnsEmpty() {
        assertTrue(apiKeyService.validate(null).isEmpty());
        assertTrue(apiKeyService.validate("plain-key").isEmpty());
    }

    @Test
    void validate_unknownKey_returnsEmpty() {
        when(apiKeyRepository.findByKeyHash(any())).thenReturn(Optional.empty());
        assertTrue(apiKeyService.validate("bhk_abcdef_ghijklmnopqrstuvwxyz").isEmpty());
    }

    @Test
    void validate_revokedKey_returnsEmpty() {
        when(apiKeyRepository.findByKeyHash(any())).thenAnswer(inv -> {
            ApiKey key = new ApiKey();
            key.setStatus(ApiKey.ApiKeyStatus.REVOKED);
            return Optional.of(key);
        });
        assertTrue(apiKeyService.validate("bhk_abcdef_ghijklmnopqrstuvwxyz").isEmpty());
    }

    @Test
    void revoke_marksRevoked() {
        ApiKey key = new ApiKey();
        key.setId(1L);
        when(apiKeyRepository.findById(1L)).thenReturn(Optional.of(key));
        when(apiKeyRepository.save(any(ApiKey.class))).thenReturn(key);

        apiKeyService.revoke(1L);

        assertEquals(ApiKey.ApiKeyStatus.REVOKED, key.getStatus());
        assertNotNull(key.getRevokedAt());
    }

    @Test
    void revoke_missingKey_throws() {
        when(apiKeyRepository.findById(99L)).thenReturn(Optional.empty());
        assertThrows(ResourceNotFoundException.class, () -> apiKeyService.revoke(99L));
    }

    @Test
    void rotate_revokesOldAndCreatesNew() {
        ApiKey existing = new ApiKey();
        existing.setId(1L);
        existing.setName("Partner");
        existing.setPartnerId(7L);
        when(apiKeyRepository.findById(1L)).thenReturn(Optional.of(existing));
        when(apiKeyRepository.save(any(ApiKey.class))).thenAnswer(inv -> inv.getArgument(0));

        ApiKeyService.CreatedApiKey rotated = apiKeyService.rotate(1L, "orders:write", null);

        assertEquals(ApiKey.ApiKeyStatus.REVOKED, existing.getStatus());
        assertNotNull(rotated.apiKey());
        assertEquals("Partner", rotated.name());
    }

    @Test
    void validate_legacySha256Key_transparentlyUpgradedToHmac() throws Exception {
        // Pre-upgrade keys stored a plain SHA-256 digest; validation must still
        // accept them and immediately upgrade the stored hash to the HMAC form.
        String presentedKey = "bhk_abcdef_ghijklmnopqrstuvwxyz123456";
        String legacyHash = legacySha256(presentedKey);

        ApiKey key = new ApiKey();
        key.setId(7L);
        key.setKeyHash(legacyHash);
        key.setStatus(ApiKey.ApiKeyStatus.ACTIVE);
        key.setPartnerId(9L);

        when(apiKeyRepository.findByKeyHash(any())).thenAnswer(inv -> {
            String lookedUp = inv.getArgument(0);
            return legacyHash.equals(lookedUp) ? Optional.of(key) : Optional.empty();
        });
        when(apiKeyRepository.save(any(ApiKey.class))).thenAnswer(inv -> inv.getArgument(0));

        Optional<ApiKey> result = apiKeyService.validate(presentedKey);

        assertTrue(result.isPresent());
        assertEquals(9L, result.get().getPartnerId());
        assertNotEquals(legacyHash, result.get().getKeyHash(),
                "the stored digest must be upgraded to the pepper-keyed HMAC");
        verify(apiKeyRepository).save(key);
    }

    @Test
    void hash_isDeterministicHmacSha256() {
        String a = apiKeyService.hash("abc");
        String b = apiKeyService.hash("abc");
        assertEquals(a, b);
        assertEquals(64, a.length());
        assertNotEquals(a, apiKeyService.hash("abd"));
    }

    @Test
    void hash_differentPepper_producesDifferentResult() {
        ApiKeyService other = new ApiKeyService(apiKeyRepository, "different-pepper");
        assertNotEquals(apiKeyService.hash("abc"), other.hash("abc"),
                "the pepper must influence the hash output");
    }

    private static String legacySha256(String value) throws NoSuchAlgorithmException {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        byte[] bytes = digest.digest(value.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        StringBuilder sb = new StringBuilder();
        for (byte b : bytes) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }
}
