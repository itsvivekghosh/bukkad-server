package com.bhukkad.identity.config;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.jwk.KeyUse;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.security.interfaces.RSAPrivateCrtKey;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.RSAPublicKeySpec;
import java.util.Base64;
import java.util.List;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Loads (or provisions) identity's RS256 signing keypair (ADR-004 step 1).
 *
 * <p>Precedence (env-first, {@link RsaKeyProperties}): inline PEM → PEM file
 * → auto-generated keypair persisted under {@code app.jwt.rsa.key-dir}
 * (k8s Secret mount path in production).</p>
 *
 * <p>Fail-fast: in prod/staging, when neither PEM env nor file is present the
 * bean refuses to boot — silently generating a per-replica ephemeral key in
 * production would make every replica sign with a different key and break
 * JWKS verification fleet-wide. The prod/staging flag is resolved by
 * {@code JwtSigningKeyConfig} so this package stays Spring-lean.</p>
 *
 * <p>The public half is exposed as a JWKS {@link JWKSet} (public keys only —
 * the private exponent never leaves this bean) by {@code JwksController}.</p>
 */
public class RsaSigningKeys {

    private static final Logger log = LoggerFactory.getLogger(RsaSigningKeys.class);
    private static final int KEY_SIZE_BITS = 2048;
    private static final Pattern PEM_HEADER = Pattern.compile("-----BEGIN ([A-Z ]+)-----");

    private final RSAKey signingKey;

    public RsaSigningKeys(RsaKeyProperties properties, boolean strictProfile) {
        this.signingKey = load(properties, strictProfile);
    }

    private static RSAKey load(RsaKeyProperties properties, boolean strictProfile) {
        if (properties.privateKeyPem() != null && !properties.privateKeyPem().isBlank()) {
            log.info("RSA signing key: loaded from inline PEM (JWT_PRIVATE_KEY_PEM)");
            return fromPkcs8Pem(properties.privateKeyPem(), properties.keyId());
        }
        if (properties.privateKeyPath() != null && !properties.privateKeyPath().isBlank()) {
            Path path = Path.of(properties.privateKeyPath());
            if (Files.isReadable(path)) {
                log.info("RSA signing key: loaded PEM file {}", properties.privateKeyPath());
                try {
                    return fromPkcs8Pem(Files.readString(path, StandardCharsets.UTF_8), properties.keyId());
                } catch (IOException e) {
                    throw new IllegalStateException("Cannot read RSA private key PEM at " + path, e);
                }
            }
            if (strictProfile) {
                throw new IllegalStateException(
                        "JWT_PRIVATE_KEY_PATH points to a missing/unreadable file: " + path
                                + " (prod/staging fail-fast, ADR-004)");
            }
            log.warn("RSA signing key PEM file {} not readable; falling back to generated key", path);
        }
        // Auto-generate + persist under key-dir. Never acceptable as the
        // production provisioning path: without env-provided keys each
        // replica would hold a different key.
        if (strictProfile) {
            throw new IllegalStateException(
                    "RS256 signing key required in prod/staging: set JWT_PRIVATE_KEY_PEM or "
                            + "JWT_PRIVATE_KEY_PATH (PEM file, e.g. a k8s Secret volume). "
                            + "Auto-generated keys are only for development (ADR-004)");
        }
        return generateAndPersist(properties);
    }

    private static RSAKey fromPkcs8Pem(String pem, String keyIdOverride) {
        String body = pem.trim();
        Matcher header = PEM_HEADER.matcher(body);
        if (header.find() && body.contains("BEGIN RSA PRIVATE KEY")) {
            throw new IllegalStateException(
                    "PKCS#1 (BEGIN RSA PRIVATE KEY) PEMs are not supported; convert with "
                            + "'openssl pkcs8 -topk8 -nocrypt' and provide PKCS#8 PEM");
        }
        byte[] pkcs8 = decodePem(body);
        try {
            KeyFactory factory = KeyFactory.getInstance("RSA");
            PrivateKey privateKey = factory.generatePrivate(new PKCS8EncodedKeySpec(pkcs8));
            if (!(privateKey instanceof RSAPrivateCrtKey crt)) {
                throw new IllegalStateException("Expected an RSAPrivateCrtKey (PKCS#8 RSA PEM)");
            }
            RSAPublicKey publicKey = (RSAPublicKey) factory.generatePublic(
                    new RSAPublicKeySpec(crt.getModulus(), crt.getPublicExponent()));
            RSAKey.Builder builder = new RSAKey.Builder(publicKey)
                    .keyUse(KeyUse.SIGNATURE)
                    .privateKey(privateKey);
            builder.keyID(keyIdOverride != null && !keyIdOverride.isBlank()
                    ? keyIdOverride
                    : thumbprintOf(publicKey));
            return builder.build();
        } catch (Exception e) {
            throw new IllegalStateException("Cannot load RSA private key from PEM: " + e.getMessage(), e);
        }
    }

    private static RSAKey generateAndPersist(RsaKeyProperties properties) {
        Path dir = Path.of(properties.keyDir());
        Path pemFile = dir.resolve("private-key.pem");
        try {
            if (Files.isReadable(pemFile)) {
                log.info("RSA signing key: reusing persisted key at {}", pemFile);
                return fromPkcs8Pem(Files.readString(pemFile, StandardCharsets.UTF_8), properties.keyId());
            }
            KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
            generator.initialize(KEY_SIZE_BITS);
            KeyPair pair = generator.generateKeyPair();
            Files.createDirectories(dir);
            Files.writeString(pemFile, toPkcs8Pem(pair.getPrivate()), StandardCharsets.UTF_8);
            // 0600-ish permissions where the filesystem supports POSIX.
            try {
                Files.setPosixFilePermissions(pemFile,
                        java.nio.file.attribute.PosixFilePermissions.fromString("rw-------"));
            } catch (UnsupportedOperationException ignored) {
                // Non-POSIX filesystem: keep going.
            }
            log.info("RSA signing key: generated new keypair and persisted to {}", pemFile);

            RSAPublicKey publicKey = (RSAPublicKey) pair.getPublic();
            RSAKey.Builder builder = new RSAKey.Builder(publicKey)
                    .keyUse(KeyUse.SIGNATURE)
                    .privateKey(pair.getPrivate());
            builder.keyID(properties.keyId() != null && !properties.keyId().isBlank()
                    ? properties.keyId()
                    : thumbprintOf(publicKey));
            return builder.build();
        } catch (Exception e) {
            throw new IllegalStateException("Cannot provision RSA signing key under " + dir
                    + ": " + e.getMessage(), e);
        }
    }

    /** PKCS#8 PEM with 64-char lines, as OpenSSL writes them. */
    private static String toPkcs8Pem(PrivateKey key) {
        String base64 = Base64.getMimeEncoder(64, "\n".getBytes(StandardCharsets.US_ASCII))
                .encodeToString(key.getEncoded());
        return "-----BEGIN PRIVATE KEY-----\n" + base64 + "\n-----END PRIVATE KEY-----\n";
    }

    private static byte[] decodePem(String pem) {
        String body = pem.replaceAll("-----BEGIN [A-Z ]+-----", "")
                .replaceAll("-----END [A-Z ]+-----", "")
                .replaceAll("\\s", "");
        try {
            return Base64.getDecoder().decode(body);
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException("Private key PEM is not valid base64", e);
        }
    }

    /** RFC 7638 thumbprint — deterministic per key, stable across replicas. */
    private static String thumbprintOf(RSAPublicKey key) {
        try {
            return new RSAKey.Builder(key).build().computeThumbprint().toString();
        } catch (JOSEException e) {
            return UUID.randomUUID().toString();
        }
    }

    /** The key identity signs access tokens with (contains the private key). */
    public RSAKey signingKey() {
        return signingKey;
    }

    /** Public-only JWKS served at {@code /.well-known/jwks.json}. */
    public JWKSet publicJwks() {
        return new JWKSet(List.of(signingKey.toPublicJWK()));
    }
}
