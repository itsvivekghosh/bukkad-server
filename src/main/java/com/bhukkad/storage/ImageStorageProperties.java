package com.bhukkad.storage;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

@Getter
@Setter
@ConfigurationProperties(prefix = "app.storage.s3")
public class ImageStorageProperties {

    private boolean enabled = false;
    private String bucket = "bhukkad-menu-images";
    private String region = "ap-south-1";
    private String keyPrefix = "menu-items";
    private long uploadUrlExpirySeconds = 900;
    private long downloadUrlExpirySeconds = 3600;

    /** CloudFront CDN distribution in front of the S3 bucket (menu images). */
    private CloudFront cloudfront = new CloudFront();

    @Getter
    @Setter
    public static class CloudFront {
        private boolean enabled = false;
        private String domain;
        private String keyPairId;
        private String privateKey;
        private long signedUrlExpirySeconds = 3600;

        /** Whether a CloudFront domain is configured. */
        public boolean isConfigured() {
            return enabled && domain != null && !domain.isBlank();
        }
    }
}
