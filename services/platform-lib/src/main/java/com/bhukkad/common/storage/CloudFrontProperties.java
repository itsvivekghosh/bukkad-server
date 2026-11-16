package com.bhukkad.common.storage;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * CloudFront configuration for private-object signed URLs.
 *
 * <p>Configuration prefix: {@code app.cloudfront}.
 *
 * <p>Security note: the private key must never be committed in configuration. In
 * production it is retrieved from AWS Secrets Manager
 * (secret {@code bhukkad/prod/cloudfront-signing-key}) at startup and injected here.
 * See docs/cdn-setup.md.
 */
@Getter
@Setter
@ConfigurationProperties(prefix = "app.cloudfront")
public class CloudFrontProperties {

    /** CloudFront key pair ID used to sign private-object URLs. */
    private String keyPairId;

    /** PEM-encoded RSA private key (PKCS#8) used to sign URLs. Real key from Secrets Manager. */
    private String privateKey;
}
