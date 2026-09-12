package com.bhukkad.identity.config;


import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * RS256 signing-key provisioning for identity (ADR-004 step 1: identity
 * issues access tokens with RS256 + kid and serves the JWKS).
 *
 * <p>Provisioning order is env-first:</p>
 * <ol>
 *   <li>{@code private-key-pem} — inline PKCS#8 PEM (env
 *       {@code JWT_PRIVATE_KEY_PEM}; preferred in production, one shared key
 *       across replicas)</li>
 *   <li>{@code private-key-path} — a PEM file (env
 *       {@code JWT_PRIVATE_KEY_PATH}; e.g. a k8s Secret volume mount)</li>
 *   <li>{@code key-dir} — auto-generated keypair persisted as
 *       {@code private-key.pem} under this directory (env
 *       {@code JWT_RSA_KEY_DIR}; dev convenience so restarts keep the same
 *       key and the JWKS stays stable). Production must NOT rely on this:
 *       the key is missing in prod unless env/files are provided → boot
 *       fails fast.</li>
 * </ol>
 *
 * @param privateKeyPem  inline PKCS#8 PEM (highest precedence)
 * @param privateKeyPath PEM file path (second)
 * @param keyDir         persistence directory for the dev auto-generated key
 * @param keyId          optional stable kid override; defaults to the RFC 7638
 *                       thumbprint of the public key (stable per key)
 */
@ConfigurationProperties(prefix = "app.jwt.rsa")
public record RsaKeyProperties(String privateKeyPem, String privateKeyPath, String keyDir, String keyId) {

    public RsaKeyProperties {
        if (keyDir == null || keyDir.isBlank()) {
            keyDir = System.getProperty("user.home") + "/.bhukkad/rsa-keys";
        }
    }
}
