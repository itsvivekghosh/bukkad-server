package com.bhukkad.common.storage;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * S3/CloudFront storage configuration binding and the CDN readiness gate.
 */
class ImageStoragePropertiesTest {

    @Test
    void defaults_areSafeAndDisabled() {
        ImageStorageProperties props = new ImageStorageProperties();
        assertThat(props.isEnabled()).isFalse();
        assertThat(props.getBucket()).isEqualTo("bhukkad-menu-images");
        assertThat(props.getRegion()).isEqualTo("ap-south-1");
        assertThat(props.getKeyPrefix()).isEqualTo("menu-items");
        assertThat(props.getUploadUrlExpirySeconds()).isEqualTo(900);
        assertThat(props.getDownloadUrlExpirySeconds()).isEqualTo(3600);
        assertThat(props.getCloudfront()).isNotNull();
        assertThat(props.getCloudfront().isConfigured()).isFalse();
    }

    @Test
    void setters_applyAndCloudFrontGateRequiresEnabledAndDomain() {
        ImageStorageProperties props = new ImageStorageProperties();
        props.setEnabled(true);
        props.setBucket("other");
        props.setRegion("eu-west-1");
        props.setKeyPrefix("p");
        props.setUploadUrlExpirySeconds(60);
        props.setDownloadUrlExpirySeconds(120);
        assertThat(props.isEnabled()).isTrue();
        assertThat(props.getBucket()).isEqualTo("other");
        assertThat(props.getRegion()).isEqualTo("eu-west-1");
        assertThat(props.getKeyPrefix()).isEqualTo("p");
        assertThat(props.getUploadUrlExpirySeconds()).isEqualTo(60);
        assertThat(props.getDownloadUrlExpirySeconds()).isEqualTo(120);

        ImageStorageProperties.CloudFront cdn = props.getCloudfront();
        cdn.setKeyPairId("AKIA-test");
        cdn.setPrivateKey("pem-secret");
        cdn.setSignedUrlExpirySeconds(30);
        assertThat(cdn.getKeyPairId()).isEqualTo("AKIA-test");
        assertThat(cdn.getPrivateKey()).isEqualTo("pem-secret");
        assertThat(cdn.getSignedUrlExpirySeconds()).isEqualTo(30);

        // enabled but no domain → not configured; blank domain too
        cdn.setEnabled(true);
        assertThat(cdn.isConfigured()).isFalse();
        cdn.setDomain("   ");
        assertThat(cdn.isConfigured()).isFalse();
        cdn.setDomain("d-123.cloudfront.net");
        assertThat(cdn.isConfigured()).isTrue();
        // domain present but disabled → still false
        cdn.setEnabled(false);
        assertThat(cdn.isConfigured()).isFalse();
    }
}
