package com.bhukkad.security;

import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.util.Base64;
import java.util.Map;

/**
 * Generates and holds an RSA key pair used for client-side password encryption.
 *
 * <p>The public key is exposed via {@code GET /v1/auth/encryption-key} so that
 * the frontend can encrypt passwords before transmission. The private key never
 * leaves this service. Key pairs are generated on startup and rotated daily to
 * limit the impact of any undetected compromise.
 */
@Service
@Slf4j
public class ClientEncryptionKeyService {

    private static final int RSA_KEY_SIZE = 2048;

    private volatile KeyPair keyPair;
    private volatile long keyExpiryEpochSecond;

    @PostConstruct
    public void init() {
        generateKeyPair();
    }

    /**
     * Returns the RSA public key as a JWK (JSON Web Key) string.
     * JWK format is directly consumable by Web Crypto API's {@code importKey}.
     */
    public String getPublicKeyJwk() {
        try {
            RSAPublicKey pub = (RSAPublicKey) keyPair.getPublic();
            Map<String, Object> jwk = new java.util.LinkedHashMap<>();
            jwk.put("kty", "RSA");
            jwk.put("n", base64UrlEncode(pub.getModulus().toByteArray()));
            jwk.put("e", base64UrlEncode(pub.getPublicExponent().toByteArray()));
            jwk.put("alg", "RSA-OAEP-256");
            jwk.put("use", "enc");
            return new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(jwk);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to encode public key as JWK", e);
        }
    }

    private static String base64UrlEncode(byte[] bytes) {
        // Strip leading zero byte if present (BigInteger can add it)
        int start = bytes[0] == 0 ? 1 : 0;
        return Base64.getUrlEncoder().withoutPadding().encodeToString(
                java.util.Arrays.copyOfRange(bytes, start, bytes.length));
    }

    /** Returns the Unix epoch second when this key pair expires. */
    public long keyExpiryEpochSecond() {
        return keyExpiryEpochSecond;
    }

    /** Returns the RSA public key (raw object) for JWE operations. */
    public RSAPublicKey getPublicKey() {
        return (RSAPublicKey) keyPair.getPublic();
    }

    /** Returns the RSA private key for JWE decryption. */
    public RSAPrivateKey getPrivateKey() {
        return (RSAPrivateKey) keyPair.getPrivate();
    }

    /** Regenerates the key pair — called daily by a scheduler or manually for rotation. */
    public void generateKeyPair() {
        try {
            KeyPairGenerator kpg = KeyPairGenerator.getInstance("RSA");
            kpg.initialize(RSA_KEY_SIZE);
            keyPair = kpg.generateKeyPair();
            // Rotate every 24 hours
            keyExpiryEpochSecond = System.currentTimeMillis() / 1000 + 86400L;
            log.info("CLIENT_ENCRYPTION_KEY_ROTATED | expiresAtEpoch={}", keyExpiryEpochSecond);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to generate RSA key pair", e);
        }
    }
}
