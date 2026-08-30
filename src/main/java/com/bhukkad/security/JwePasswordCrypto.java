package com.bhukkad.security;

import com.nimbusds.jose.JWEObject;
import com.nimbusds.jose.Payload;
import com.nimbusds.jose.crypto.RSADecrypter;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.security.interfaces.RSAPrivateKey;
import java.time.Instant;
import java.util.Map;
import java.util.Map;
import java.util.UUID;

/**
 * Encrypts and decrypts JWE tokens for password fields transmitted from the
 * frontend. Uses RSA-OAEP-256 for key encryption and AES-256-GCM for content
 * encryption.
 *
 * <p>Each encrypted payload includes:
 * - {@code password} — the plaintext password (only readable after decryption)
 * - {@code nonce} — random UUID to prevent replay
 * - {@code timestamp} — Unix epoch seconds for timestamp validation
 *
 * <p>Replay protection: {@link ReplayNonceValidator} checks that each nonce
 * is unique and timestamps are within an acceptable window.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class JwePasswordCrypto {

    private static final long MAX_TIMESTAMP_SKEW_SECONDS = 300L; // 5 minutes

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final ClientEncryptionKeyService keyService;

    /**
     * Decrypts a JWE payload and validates the nonce/timestamp.
     *
     * @param jweString the compact JWE string from the client
     * @return DecryptedPayload containing password, nonce, and timestamp
     * @throws SecurityException if decryption fails or payload is invalid
     */
    public DecryptedPayload decrypt(String jweString) {
        try {
            RSAPrivateKey privateKey = keyService.getPrivateKey();
            RSADecrypter decrypter = new RSADecrypter(privateKey);

            JWEObject jweObject = JWEObject.parse(jweString);
            jweObject.decrypt(decrypter);

            Payload payload = jweObject.getPayload();
            String json = payload.toString();
            Map<String, Object> claims = MAPPER.readValue(json, Map.class);

            String password = (String) claims.get("password");
            String nonce = (String) claims.get("nonce");
            Long timestamp = (Number) claims.get("timestamp") != null
                    ? ((Number) claims.get("timestamp")).longValue()
                    : null;

            if (password == null || nonce == null || timestamp == null) {
                throw new SecurityException("Encrypted payload missing required fields");
            }

            // Validate timestamp is within the acceptable skew window
            long now = Instant.now().getEpochSecond();
            if (Math.abs(now - timestamp) > MAX_TIMESTAMP_SKEW_SECONDS) {
                log.warn("JWE_REPLAY_REJECTED | timestamp={} | now={} | skew={}",
                        timestamp, now, Math.abs(now - timestamp));
                throw new SecurityException(
                        String.format("Token timestamp is too far from current time (skew=%ds > %ds)",
                                Math.abs(now - timestamp), MAX_TIMESTAMP_SKEW_SECONDS));
            }

            log.debug("JWE_DECRYPTED | nonce={} | ts={}", nonce, timestamp);
            return new DecryptedPayload(password, nonce, timestamp);

        } catch (Exception e) {
            log.warn("JWE_DECRYPT_FAILED | error={}", e.getMessage());
            throw new SecurityException("Failed to decrypt payload", e);
        }
    }

    /**
     * Decrypts a JWE string and returns only the password.
     * Convenience method for auth flows.
     */
    public String decryptPassword(String jweString) {
        return decrypt(jweString).password();
    }

    /**
     * Encrypted and authenticated payload returned after decryption.
     */
    public record DecryptedPayload(String password, String nonce, long timestamp) {}
}
