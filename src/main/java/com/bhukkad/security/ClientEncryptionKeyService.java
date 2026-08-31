package com.bhukkad.security;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.time.Duration;
import java.util.Base64;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * Generates and holds an RSA key pair used for client-side password encryption.
 *
 * <p>The public key is exposed via {@code GET /v1/auth/encryption-key} so that
 * the frontend can encrypt passwords before transmission. The private key is
 * needed to decrypt those passwords at login.</p>
 *
 * <p><b>Multi-pod correctness:</b> the key pair is stored in Redis (key
 * {@code bhukkad:encryption:keypair}) so every replica serves and decrypts with
 * the SAME pair — an in-memory-per-instance pair would break logins routed to a
 * different pod than the one that served the public key. Rotation is driven by
 * {@link ClientEncryptionKeyRotationScheduler} under a ShedLock so only one
 * replica rotates at a time; the previous private key is kept in memory for a
 * short grace window so an in-flight JWE that used the old public key still
 * decrypts. When Redis is unavailable the service falls back to a purely
 * in-memory pair (single-instance / degraded operation).</p>
 */
@Service
@Slf4j
public class ClientEncryptionKeyService {

    private static final int RSA_KEY_SIZE = 2048;
    /** Grace period after rotation during which the previous private key still decrypts. */
    private static final Duration KEY_GRACE_WINDOW = Duration.ofMinutes(5);

    private static final String KEYPAIR_REDIS_KEY = "bhukkad:encryption:keypair";
    private static final Duration KEYPAIR_REDIS_TTL = Duration.ofHours(26);

    /** Redis read timeout — bounded so a cold-start Redis outage fails fast. */
    private static final Duration REDIS_OP_TIMEOUT = Duration.ofSeconds(3);

    private volatile KeyPair keyPair;
    private volatile long keyExpiryEpochSecond;
    /** Previous key kept for {@link #KEY_GRACE_WINDOW} so in-flight JWEs decrypt. */
    private volatile KeyPair previousKeyPair;
    private volatile long previousKeyExpiryEpochSecond;

    private final StringRedisTemplate redisTemplate;

    /**
     * Dedicated single-thread executor for Redis reads/writes. Using the shared
     * ForkJoinPool lets a slow/broken Redis connection hold a common-pool
     * thread while blocked on the Lettuce connection-factory lock, which in
     * turn blocks unrelated Redis callers and can starve startup. A dedicated
     * executor with bounded timeouts isolates this service's Redis traffic.
     */
    private final java.util.concurrent.ExecutorService redisExecutor =
            java.util.concurrent.Executors.newSingleThreadExecutor(r -> {
                Thread t = new Thread(r, "client-encryption-redis");
                t.setDaemon(true);
                return t;
            });

    /** Test / degraded constructor — in-memory only. */
    public ClientEncryptionKeyService() {
        this(null);
    }

    @Autowired
    public ClientEncryptionKeyService(StringRedisTemplate redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    @PostConstruct
    public void init() {
        // Prefer the shared Redis pair so all pods agree on the same key.
        if (!loadFromRedis()) {
            generateKeyPair();
        }
    }

    @PreDestroy
    public void shutdown() {
        redisExecutor.shutdownNow();
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

    /**
     * Returns the RSA private key for JWE decryption. A rotation within the
     * grace window is handled by {@code JwePasswordCrypto}, which falls back to
     * {@link #previousPrivateKey()} when the current key does not decrypt.
     */
    public RSAPrivateKey getPrivateKey() {
        return (RSAPrivateKey) keyPair.getPrivate();
    }

    /** Returns the previous (rotated-out) private key while still in grace. */
    RSAPrivateKey previousPrivateKey() {
        return previousKeyPair == null ? null : (RSAPrivateKey) previousKeyPair.getPrivate();
    }

    /** True while a rotated-out key is still within its decryption grace window. */
    boolean hasPreviousKeyInGrace() {
        return previousKeyPair != null
                && previousKeyExpiryEpochSecond > System.currentTimeMillis() / 1000;
    }

    /**
     * Regenerates the key pair — called daily by a scheduler or manually for
     * rotation. Persists the new pair to Redis so all replicas converge.
     */
    public void generateKeyPair() {
        try {
            KeyPairGenerator kpg = KeyPairGenerator.getInstance("RSA");
            kpg.initialize(RSA_KEY_SIZE);
            KeyPair generated = kpg.generateKeyPair();
            long expiry = System.currentTimeMillis() / 1000 + 86400L;

            // Keep the current pair as the "previous" one for the grace window
            // so in-flight JWE payloads that used the old public key still
            // decrypt during a rotation.
            if (keyPair != null) {
                previousKeyPair = keyPair;
                previousKeyExpiryEpochSecond = keyExpiryEpochSecond;
            }
            keyPair = generated;
            keyExpiryEpochSecond = expiry;

            // Persist to Redis asynchronously so a slow Redis does not block
            // Spring bean initialization or startup. Runs on a dedicated
            // bounded executor (not the shared ForkJoinPool) so a Redis
            // outage cannot tie up a common-pool thread on the connection
            // factory lock.
            redisExecutor.submit(() -> persistToRedis(generated, expiry));
            log.info("CLIENT_ENCRYPTION_KEY_ROTATED | expiresAtEpoch={}", expiry);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to generate RSA key pair", e);
        }
    }

    private void persistToRedis(KeyPair pair, long expiryEpochSecond) {
        if (redisTemplate == null) {
            return;
        }
        try {
            String privateKeyB64 = Base64.getEncoder().encodeToString(pair.getPrivate().getEncoded());
            String publicKeyB64 = Base64.getEncoder().encodeToString(pair.getPublic().getEncoded());
            String payload = privateKeyB64 + "." + publicKeyB64 + "." + expiryEpochSecond;
            redisTemplate.opsForValue().set(KEYPAIR_REDIS_KEY, payload, KEYPAIR_REDIS_TTL);
        } catch (Exception e) {
            log.warn("CLIENT_ENCRYPTION_KEY_REDIS_WRITE_FAILED | error={}", e.getMessage());
        }
    }

    private boolean loadFromRedis() {
        if (redisTemplate == null) {
            return false;
        }
        try {
            CompletableFuture<String> future = CompletableFuture.supplyAsync(() -> {
                try {
                    return redisTemplate.opsForValue().get(KEYPAIR_REDIS_KEY);
                } catch (Exception e) {
                    log.warn("CLIENT_ENCRYPTION_KEY_REDIS_READ_FAILED | error={}", e.getMessage());
                    return null;
                }
            }, redisExecutor);
            String payload = future.get(REDIS_OP_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
            if (payload == null || payload.isBlank()) {
                return false;
            }
            String[] parts = payload.split("\\.");
            if (parts.length != 3) {
                return false;
            }
            KeyFactory kf = KeyFactory.getInstance("RSA");
            PrivateKey priv = kf.generatePrivate(new PKCS8EncodedKeySpec(Base64.getDecoder().decode(parts[0])));
            PublicKey pub = kf.generatePublic(new X509EncodedKeySpec(Base64.getDecoder().decode(parts[1])));
            long expiry = Long.parseLong(parts[2]);
            keyPair = new KeyPair(pub, priv);
            keyExpiryEpochSecond = expiry;
            log.info("CLIENT_ENCRYPTION_KEY_LOADED_FROM_REDIS | expiresAtEpoch={}", expiry);
            return true;
        } catch (java.util.concurrent.TimeoutException e) {
            log.warn("CLIENT_ENCRYPTION_KEY_REDIS_LOAD_TIMEOUT | falling back to in-memory key");
            return false;
        } catch (Exception e) {
            log.warn("CLIENT_ENCRYPTION_KEY_REDIS_READ_FAILED | error={}", e.getMessage());
            return false;
        }
    }
}
