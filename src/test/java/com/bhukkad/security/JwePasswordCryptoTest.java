package com.bhukkad.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nimbusds.jose.EncryptionMethod;
import com.nimbusds.jose.JWEAlgorithm;
import com.nimbusds.jose.JWEHeader;
import com.nimbusds.jose.JWEObject;
import com.nimbusds.jose.Payload;
import com.nimbusds.jose.crypto.RSAEncrypter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.security.interfaces.RSAPublicKey;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class JwePasswordCryptoTest {

    @Mock
    private StringRedisTemplate redisTemplate;

    @Mock
    private ValueOperations<String, String> valueOps;

    private JwePasswordCrypto crypto;
    private ClientEncryptionKeyService realKeyService;

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @BeforeEach
    void setUp() {
        realKeyService = new ClientEncryptionKeyService();
        realKeyService.init();
        crypto = new JwePasswordCrypto(realKeyService);
    }

    @Test
    void decryptPassword_throwsOnInvalidJwe() {
        assertThrows(SecurityException.class, () -> crypto.decryptPassword("not-a-jwe"));
    }

    @Test
    void decrypt_throwsOnNullInput() {
        assertThrows(SecurityException.class, () -> crypto.decrypt(null));
    }

    @Test
    void encryptDecrypt_roundTrip_preservesPasswordAndNonce() throws Exception {
        RSAPublicKey pub = realKeyService.getPublicKey();
        RSAEncrypter encrypter = new RSAEncrypter(pub);

        Map<String, Object> payload = new HashMap<>();
        payload.put("password", "mySecretPassword123");
        payload.put("nonce", UUID.randomUUID().toString());
        payload.put("timestamp", Instant.now().getEpochSecond());

        String jsonPayload = MAPPER.writeValueAsString(payload);

        JWEHeader header = new JWEHeader.Builder(JWEAlgorithm.RSA_OAEP_256, EncryptionMethod.A256GCM)
                .contentType("JWT")
                .build();

        JWEObject jweObject = new JWEObject(header, new Payload(jsonPayload));
        jweObject.encrypt(encrypter);
        String jweString = jweObject.serialize();

        var decrypted = crypto.decrypt(jweString);

        assertEquals("mySecretPassword123", decrypted.password());
        assertNotNull(decrypted.nonce());
        assertTrue(decrypted.timestamp() > 0);
    }
}
