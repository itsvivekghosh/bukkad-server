package com.bhukkad.config;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Validates the production NGINX gateway configuration enforces rate limiting,
 * HTTP/2, TLS, and security headers expected by the production-readiness checklist.
 */
class NginxConfigTest {

    private static final Path NGINX_CONF = Paths.get("docker/nginx/nginx.conf");
    private static final Path BHUKKAD_CONF = Paths.get("docker/nginx/conf.d/bhukkad.conf");

    @Test
    void bhukkadConf_hasHttp2AndTls() throws IOException {
        String conf = Files.readString(BHUKKAD_CONF, StandardCharsets.UTF_8);
        assertTrue(conf.contains("listen 443 ssl http2"),
                "NGINX server block must listen on 443 with HTTP/2 and TLS");
        assertTrue(conf.contains("ssl_certificate"), "TLS certificate must be configured");
        assertTrue(conf.contains("ssl_protocols"), "TLS protocols must be configured");
        assertTrue(conf.contains("TLSv1.2"), "Must support TLS 1.2");
        assertTrue(conf.contains("TLSv1.3"), "Must support TLS 1.3");
    }

    @Test
    void nginxConf_hasRateLimitingZones() throws IOException {
        String conf = Files.readString(NGINX_CONF, StandardCharsets.UTF_8);
        assertTrue(conf.contains("limit_req_zone"), "Rate-limit zones must be defined");
        assertTrue(conf.contains("zone=api_general"), "General API rate limit required");
        assertTrue(conf.contains("zone=api_auth"), "Auth rate limit required");
        assertTrue(conf.contains("zone=api_order"), "Order rate limit required");
        assertTrue(conf.contains("limit_conn_zone"), "Connection limit zone required");
    }

    @Test
    void bhukkadConf_blocksSwaggerInProd() throws IOException {
        String conf = Files.readString(BHUKKAD_CONF, StandardCharsets.UTF_8);
        assertTrue(conf.contains("location /swagger-ui"), "Swagger UI block must exist");
        assertTrue(conf.contains("location /v3/api-docs"), "OpenAPI docs block must exist");
        assertTrue(conf.contains("return 404"), "Swagger must return 404 in prod");
    }

    @Test
    void bhukkadConf_hasSecurityHeaders() throws IOException {
        String conf = Files.readString(BHUKKAD_CONF, StandardCharsets.UTF_8);
        assertTrue(conf.contains("X-Frame-Options DENY"), "X-Frame-Options header required");
        assertTrue(conf.contains("X-Content-Type-Options nosniff"), "X-Content-Type-Options required");
        assertTrue(conf.contains("Strict-Transport-Security"), "HSTS header required");
        assertTrue(conf.contains("X-XSS-Protection"), "X-XSS-Protection header required");
    }

    @Test
    void nginxConf_proxiesToAppUpstream() throws IOException {
        String conf = Files.readString(NGINX_CONF, StandardCharsets.UTF_8);
        assertTrue(conf.contains("upstream bhukkad_app"), "Upstream to app service required");
        assertTrue(conf.contains("server app:8080"), "Upstream must point to app:8080");
        assertTrue(conf.contains("keepalive 32"), "Keepalive connections required for performance");
    }

    @Test
    void bhukkadConf_proxiesToAppUpstream() throws IOException {
        String conf = Files.readString(BHUKKAD_CONF, StandardCharsets.UTF_8);
        assertTrue(conf.contains("proxy_pass http://bhukkad_app"), "Proxy pass to app required");
        assertTrue(conf.contains("proxy_http_version 1.1"), "HTTP/1.1 proxy required for WebSocket/SSE");
    }

    @Test
    void bhukkadConf_hasHealthEndpoint() throws IOException {
        String conf = Files.readString(BHUKKAD_CONF, StandardCharsets.UTF_8);
        assertTrue(conf.contains("location /api/v1/health"), "Health endpoint must be proxied");
    }

    @Test
    void bhukkadConf_httpRedirectsToHttps() throws IOException {
        String conf = Files.readString(BHUKKAD_CONF, StandardCharsets.UTF_8);
        assertTrue(conf.contains("listen 80"), "HTTP listener must exist for redirects");
        assertTrue(conf.contains("return 301 https://"), "HTTP must redirect to HTTPS");
    }
}
