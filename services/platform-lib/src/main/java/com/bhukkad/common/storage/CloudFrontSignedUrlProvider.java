package com.bhukkad.common.storage;

import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.Signature;
import java.security.spec.PKCS8EncodedKeySpec;
import java.time.Instant;
import java.util.Base64;
import java.util.Objects;

import org.springframework.stereotype.Component;

import lombok.NonNull;
import lombok.extern.slf4j.Slf4j;

/**
 * Generates CloudFront signed URLs (canned policy) for private objects.
 *
 * <p>Placeholder implementation: the signing key is read from {@link CloudFrontProperties}
 * (configured via {@code app.cloudfront.key-pair-id} and
 * {@code app.cloudfront.private-key}). The real private key must be retrieved from
 * AWS Secrets Manager (secret {@code bhukkad/prod/cloudfront-signing-key}) at startup
 * and injected here — never committed in configuration or container images.
 *
 * <p>Signed URL (canned policy):
 *   {@code https://{domain}/{object}?Expires={epochSeconds}&Signature={urlSafeBase64}&Key-Pair-Id={id}}
 *
 * <p>CloudFront verifies the signature over the URL up to (but excluding) the
 * {@code Signature} and {@code Key-Pair-Id} query parameters, using RSA-SHA1 with
 * the public key matching the configured key pair.
 */
@Component
@Slf4j
public class CloudFrontSignedUrlProvider {

    private final String keyPairId;
    private final PrivateKey privateKey;

    /**
     * Creates a provider from bound CloudFront properties. The PEM private key is
     * parsed here; only PKCS#8 PEM ({@code -----BEGIN PRIVATE KEY-----}) is supported.
     * Convert PKCS#1 keys with: {@code openssl pkcs8 -topk8 -nocrypt}.
     */
    public CloudFrontSignedUrlProvider(CloudFrontProperties properties) {
        this(Objects.requireNonNull(properties.getKeyPairId(), "app.cloudfront.key-pair-id"),
                loadPrivateKey(Objects.requireNonNull(properties.getPrivateKey(), "app.cloudfront.private-key")));
    }

    /**
     * Test / DI constructor accepting a resolved {@link PrivateKey}.
     */
    public CloudFrontSignedUrlProvider(@NonNull String keyPairId, @NonNull PrivateKey privateKey) {
        this.keyPairId = keyPairId;
        this.privateKey = privateKey;
    }

    /**
     * Signs a CloudFront object URL with a canned policy expiring at {@code expiresAt}.
     *
     * @param url          Object URL (must not already contain {@code Signature} or
     *                     {@code Key-Pair-Id} query parameters)
     * @param expiresAt    Expiration instant
     * @return Signed URL usable by clients to fetch the private object via CloudFront
     */
    public String sign(String url, Instant expiresAt) {
        String policy = policyString(url, expiresAt.getEpochSecond());
        byte[] signature;
        try {
            Signature signer = Signature.getInstance("SHA1withRSA");
            signer.initSign(privateKey);
            signer.update(policy.getBytes(StandardCharsets.UTF_8));
            signature = signer.sign();
        } catch (Exception e) {
            log.error("Failed to sign CloudFront URL for keyPairId={}", keyPairId);
            throw new IllegalStateException("CloudFront URL signing failed", e);
        }
        String encoded = Base64.getUrlEncoder().withoutPadding().encodeToString(signature);
        return policy + "&Signature=" + encoded + "&Key-Pair-Id=" + keyPairId;
    }

    private static String policyString(String url, long expiresAt) {
        StringBuilder policy = new StringBuilder(url);
        if (policy.indexOf("?") >= 0) {
            policy.append("&Expires=").append(expiresAt);
        } else {
            policy.append("?Expires=").append(expiresAt);
        }
        // Strip any pre-existing Signature/Key-Pair-Id params so they are not part of the
        // signed policy (CloudFront signs everything before the Signature parameter).
        return stripSignedParams(policy.toString());
    }

    private static String stripSignedParams(String policy) {
        int sigIdx = lastParamIndex(policy, "Signature");
        int kpIdx = lastParamIndex(policy, "Key-Pair-Id");
        int cut = Math.max(sigIdx, kpIdx);
        return cut >= 0 ? policy.substring(0, cut) : policy;
    }

    private static int lastParamIndex(String policy, String name) {
        int idx = policy.indexOf("&" + name + "=");
        return idx >= 0 ? idx : -1;
    }

    private static PrivateKey loadPrivateKey(String pem) {
        String derText = pem
                .replace("-----BEGIN PRIVATE KEY-----", "")
                .replace("-----END PRIVATE KEY-----", "")
                .replace("-----BEGIN RSA PRIVATE KEY-----", "")
                .replace("-----END RSA PRIVATE KEY-----", "")
                .replaceAll("\\s", "");
        byte[] der;
        try {
            der = Base64.getDecoder().decode(derText);
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException("app.cloudfront.private-key is not valid base64 PEM", e);
        }
        try {
            return KeyFactory.getInstance("RSA").generatePrivate(new PKCS8EncodedKeySpec(der));
        } catch (Exception e) {
            throw new IllegalStateException(
                    "Unsupported private key format: provide PKCS#8 PEM (BEGIN PRIVATE KEY). "
                            + "Convert PKCS#1 with: openssl pkcs8 -topk8 -nocrypt -in key.pem -out key.pkcs8.pem",
                    e);
        }
    }
}
