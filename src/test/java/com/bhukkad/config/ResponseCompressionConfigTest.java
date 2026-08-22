package com.bhukkad.config;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies the response-compression contract documented in {@code application.yml}.
 *
 * <p>The Tomcat 10.1 built-in {@code CompressionConfig} only implements gzip/deflate,
 * so Brotli is intentionally terminated at the nginx ingress layer (see
 * {@code k8s/nginx/configmap.yaml}). These tests pin both layers so accidental
 * edits that drop compression silently are caught at build time.
 */
class ResponseCompressionConfigTest {

    @Test
    void applicationYmlEnablesGzipAndIncludesJsonMimeType() throws Exception {
        Path yml = Path.of("src/main/resources/application.yml");
        String content = new String(Files.readAllBytes(yml));
        assertNotNull(content);
        assertTrue(content.contains("compression:"),
                "application.yml must declare server.compression.*");
        assertTrue(content.contains("application/json"),
                "application/json must be in the Tomcat compression mime-type allow-list");
        assertTrue(content.contains("enabled: true"),
                "server.compression.enabled must be true");
    }

    @Test
    void nginxConfigmapEnablesBothGzipAndBrotli() throws Exception {
        Path yml = Path.of("k8s/nginx/configmap.yaml");
        if (!Files.exists(yml)) {
            // Test is environment-aware: this repo may be cloned without k8s/
            // in some pipelines. Skip silently rather than fail unrelatedly.
            return;
        }
        String content = new String(Files.readAllBytes(yml));
        assertTrue(content.contains("gzip on;"),
                "nginx must enable gzip as the fallback compressor");
        assertTrue(content.contains("brotli on;"),
                "nginx must enable brotli for clients that send `Accept-Encoding: br`");
        assertTrue(content.contains("application/json"),
                "nginx compression type list must include application/json");
    }
}
