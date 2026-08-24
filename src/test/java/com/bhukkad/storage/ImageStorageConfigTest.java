package com.bhukkad.storage;

import org.junit.jupiter.api.Test;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * Unit tests for the {@link ImageStorageConfig} bean-producing methods.
 *
 * <p>The beans are built directly with plain property objects. Building an
 * {@link S3Client}/{@link S3Presigner} performs no network calls (credential
 * resolution is lazy), so the tests stay deterministic; clients are closed
 * again to release their HTTP resources.</p>
 */
class ImageStorageConfigTest {

    @Test
    void s3Client_configuredRegion_buildsClientInThatRegion() {
        ImageStorageProperties properties = new ImageStorageProperties();
        properties.setRegion("eu-west-1");
        ImageStorageConfig config = new ImageStorageConfig(properties);

        try (S3Client client = config.s3Client()) {
            assertNotNull(client);
            assertEquals(Region.EU_WEST_1, client.serviceClientConfiguration().region());
        }
    }

    @Test
    void s3Client_defaultProperties_buildsClientWithDefaultRegion() {
        ImageStorageConfig config = new ImageStorageConfig(new ImageStorageProperties());

        try (S3Client client = config.s3Client()) {
            assertEquals(Region.AP_SOUTH_1, client.serviceClientConfiguration().region());
        }
    }

    @Test
    void s3Presigner_defaultProperties_buildsNonNullPresigner() {
        ImageStorageConfig config = new ImageStorageConfig(new ImageStorageProperties());

        try (S3Presigner presigner = config.s3Presigner()) {
            assertNotNull(presigner);
        }
    }

    @Test
    void s3Presigner_configuredRegion_buildsNonNullPresigner() {
        ImageStorageProperties properties = new ImageStorageProperties();
        properties.setRegion("us-east-1");
        ImageStorageConfig config = new ImageStorageConfig(properties);

        try (S3Presigner presigner = config.s3Presigner()) {
            assertNotNull(presigner);
        }
    }
}
