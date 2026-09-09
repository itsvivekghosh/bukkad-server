package com.bhukkad.common.security;

import com.nimbusds.jose.JOSEObjectType;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.MACSigner;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;

import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PlatformJwtValidatorTest {

    private static final String SECRET = "0123456789abcdef0123456789abcdef"; // 32 bytes

    private static String hs256Token(long userId, String email, String scope, Date expiry) throws Exception {
        JWTClaimsSet claims = new JWTClaimsSet.Builder()
                .subject(String.valueOf(userId))
                .claim("email", email)
                .claim("scope", scope)
                .issueTime(Date.from(Instant.now()))
                .expirationTime(expiry)
                .build();
        SignedJWT jwt = new SignedJWT(new JWSHeader(JWSAlgorithm.HS256), claims);
        jwt.sign(new MACSigner(SECRET));
        return jwt.serialize();
    }

    private static String rs256Token(RSAKey signingKey, long userId, String email, String scope, Date expiry)
            throws Exception {
        JWSHeader header = new JWSHeader.Builder(JWSAlgorithm.RS256)
                .type(JOSEObjectType.JWT)
                .keyID(signingKey.getKeyID())
                .build();
        JWTClaimsSet claims = new JWTClaimsSet.Builder()
                .subject(String.valueOf(userId))
                .claim("email", email)
                .claim("scope", scope)
                .issueTime(Date.from(Instant.now()))
                .expirationTime(expiry)
                .build();
        SignedJWT jwt = new SignedJWT(header, claims);
        jwt.sign(new RSASSASigner(signingKey));
        return jwt.serialize();
    }

    @Test
    void hs256_validToken_returnsPrincipal() throws Exception {
        PlatformJwtValidator validator = new PlatformJwtValidator(
                new PlatformJwtProperties(SECRET, null, null, null));

        String token = hs256Token(42L, "a@b.com", "customer",
                Date.from(Instant.now().plusSeconds(3600)));

        Optional<TokenPrincipal> principal = validator.validate(token);

        assertThat(principal).isPresent();
        assertThat(principal.get().userId()).isEqualTo(42L);
        assertThat(principal.get().email()).isEqualTo("a@b.com");
        assertThat(principal.get().scope()).isEqualTo("customer");
    }

    @Test
    void hs256_expiredToken_rejected() throws Exception {
        PlatformJwtValidator validator = new PlatformJwtValidator(
                new PlatformJwtProperties(SECRET, null, null, null));

        String token = hs256Token(42L, "a@b.com", "customer",
                Date.from(Instant.now().minusSeconds(10)));

        assertThat(validator.validate(token)).isEmpty();
    }

    @Test
    void hs256_tamperedSignature_rejected() throws Exception {
        PlatformJwtValidator validator = new PlatformJwtValidator(
                new PlatformJwtProperties(SECRET, null, null, null));

        String token = hs256Token(42L, "a@b.com", "customer",
                Date.from(Instant.now().plusSeconds(3600)));
        String tampered = token.substring(0, token.length() - 4) + "AAAA";

        assertThat(validator.validate(tampered)).isEmpty();
    }

    @Test
    void hs256_wrongSecret_rejected() throws Exception {
        PlatformJwtValidator validator = new PlatformJwtValidator(
                new PlatformJwtProperties("fedcba9876543210fedcba9876543210", null, null, null));

        String token = hs256Token(42L, "a@b.com", "customer",
                Date.from(Instant.now().plusSeconds(3600)));

        assertThat(validator.validate(token)).isEmpty();
    }

    @Test
    void garbageToken_rejected() {
        PlatformJwtValidator validator = new PlatformJwtValidator(
                new PlatformJwtProperties(SECRET, null, null, null));

        assertThat(validator.validate("not-a-jwt")).isEmpty();
        assertThat(validator.validate("")).isEmpty();
        assertThat(validator.validate(null)).isEmpty();
    }

    @Test
    void issuerMismatch_rejected() throws Exception {
        PlatformJwtValidator validator = new PlatformJwtValidator(
                new PlatformJwtProperties(SECRET, null, "https://issuer.example", null));

        String token = hs256Token(42L, "a@b.com", "customer",
                Date.from(Instant.now().plusSeconds(3600)));

        assertThat(validator.validate(token)).isEmpty();
    }

    @Test
    void audienceMismatch_rejected() throws Exception {
        PlatformJwtValidator validator = new PlatformJwtValidator(
                new PlatformJwtProperties(SECRET, null, null, "bhukkad-app"));

        JWTClaimsSet claims = new JWTClaimsSet.Builder()
                .subject("7")
                .claim("email", "a@b.com")
                .claim("scope", "customer")
                .issueTime(Date.from(Instant.now()))
                .expirationTime(Date.from(Instant.now().plusSeconds(3600)))
                .build();
        SignedJWT jwt = new SignedJWT(new JWSHeader(JWSAlgorithm.HS256), claims);
        jwt.sign(new MACSigner(SECRET));

        assertThat(validator.validate(jwt.serialize())).isEmpty();
    }

    @Test
    void jwksMode_validRs256Token_returnsPrincipal() throws Exception {
        RSAKey signingKey = new RSAKeyGenerator(2048).keyID("key-1").generate();
        RSAKey publicKey = signingKey.toPublicJWK();
        String jwksJson = new JWKSet(publicKey).toString();

        HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
        try {
            server.createContext("/jwks", exchange -> {
                byte[] body = jwksJson.getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().add("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, body.length);
                try (OutputStream os = exchange.getResponseBody()) {
                    os.write(body);
                }
            });
            server.start();

            PlatformJwtValidator validator = new PlatformJwtValidator(
                    new PlatformJwtProperties(null,
                            "http://localhost:" + server.getAddress().getPort() + "/jwks",
                            null, null));

            String token = rs256Token(signingKey, 99L, "rsa@b.com", "restaurant_owner",
                    Date.from(Instant.now().plusSeconds(3600)));

            Optional<TokenPrincipal> principal = validator.validate(token);

            assertThat(principal).isPresent();
            assertThat(principal.get().userId()).isEqualTo(99L);
            assertThat(principal.get().email()).isEqualTo("rsa@b.com");
            assertThat(principal.get().scope()).isEqualTo("restaurant_owner");
        } finally {
            server.stop(0);
        }
    }

    @Test
    void jwksMode_wrongKid_rejected() throws Exception {
        RSAKey signingKey = new RSAKeyGenerator(2048).keyID("unknown-kid").generate();
        RSAKey otherKey = new RSAKeyGenerator(2048).keyID("key-1").generate();
        String jwksJson = new JWKSet(otherKey.toPublicJWK()).toString();

        HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
        try {
            server.createContext("/jwks", exchange -> {
                byte[] body = jwksJson.getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().add("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, body.length);
                try (OutputStream os = exchange.getResponseBody()) {
                    os.write(body);
                }
            });
            server.start();

            PlatformJwtValidator validator = new PlatformJwtValidator(
                    new PlatformJwtProperties(null,
                            "http://localhost:" + server.getAddress().getPort() + "/jwks",
                            null, null));

            String token = rs256Token(signingKey, 99L, "rsa@b.com", "customer",
                    Date.from(Instant.now().plusSeconds(3600)));

            assertThat(validator.validate(token)).isEmpty();
        } finally {
            server.stop(0);
        }
    }

    @Test
    void tokenPrincipalRecord_works() {
        TokenPrincipal principal = new TokenPrincipal(1L, "u@b.com", "admin");
        assertThat(principal.userId()).isEqualTo(1L);
        assertThat(principal.email()).isEqualTo("u@b.com");
        assertThat(principal.scope()).isEqualTo("admin");
    }

    @Test
    void isEnabled_reflectsConfiguration() {
        assertThat(new PlatformJwtProperties(SECRET, null, null, null).enabled()).isTrue();
        assertThat(new PlatformJwtProperties(null, "https://example/jwks", null, null).enabled()).isTrue();
        assertThat(new PlatformJwtProperties(null, null, null, null).enabled()).isFalse();
        assertThat(new PlatformJwtProperties("", "", null, null).enabled()).isFalse();
    }

    // ─── audit V-03: stale-while-revalidate, single-flight on expiry ───────────

    @Test
    void jwksMode_concurrentExpiry_triggersExactlyOneFetch() throws Exception {
        RSAKey signingKey = new RSAKeyGenerator(2048).keyID("key-1").generate();
        String jwksJson = new JWKSet(signingKey.toPublicJWK()).toString();
        java.util.concurrent.atomic.AtomicInteger hits = new java.util.concurrent.atomic.AtomicInteger();

        HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
        try {
            server.createContext("/jwks", exchange -> {
                hits.incrementAndGet();
                byte[] body = jwksJson.getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().add("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, body.length);
                try (OutputStream os = exchange.getResponseBody()) {
                    os.write(body);
                }
            });
            server.start();

            PlatformJwtValidator validator = new PlatformJwtValidator(
                    new PlatformJwtProperties(null,
                            "http://localhost:" + server.getAddress().getPort() + "/jwks",
                            null, null));
            String token = rs256Token(signingKey, 99L, "rsa@b.com", "restaurant_owner",
                    Date.from(Instant.now().plusSeconds(3600)));

            // Cold start: exactly one blocking fetch.
            assertThat(validator.validate(token)).isPresent();
            assertThat(hits.get()).isEqualTo(1);

            // Force expiry, then 10 CONCURRENT expiring verifies. The cache is
            // stale-served while a single-flight async refresh runs: exactly
            // ONE additional HTTP fetch, and the callers never block on it.
            validator.expireJwksCacheForTests();
            java.util.concurrent.CountDownLatch start = new java.util.concurrent.CountDownLatch(1);
            java.util.List<Thread> threads = new java.util.ArrayList<>();
            for (int i = 0; i < 10; i++) {
                Thread t = new Thread(() -> {
                    try {
                        start.await();
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                    validator.validate(token);
                });
                threads.add(t);
                t.start();
            }
            start.countDown();
            for (Thread t : threads) {
                t.join(5000);
            }

            // The refresh is async — wait for it to land (bounded).
            long deadline = System.currentTimeMillis() + 5000;
            while (hits.get() < 2 && System.currentTimeMillis() < deadline) {
                Thread.sleep(20);
            }
            assertThat(hits.get()).isEqualTo(2);
            // ...and nothing after the back-off gate fires again within a short window.
            Thread.sleep(400);
            assertThat(hits.get()).isEqualTo(2);
        } finally {
            server.stop(0);
        }
    }

    @Test
    void jwksMode_expiredCache_servesStaleImmediatelyWhileRefreshing() throws Exception {
        RSAKey signingKey = new RSAKeyGenerator(2048).keyID("key-1").generate();
        String jwksJson = new JWKSet(signingKey.toPublicJWK()).toString();

        HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
        try {
            server.createContext("/jwks", exchange -> {
                byte[] body = jwksJson.getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().add("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, body.length);
                try (OutputStream os = exchange.getResponseBody()) {
                    os.write(body);
                }
            });
            server.start();

            PlatformJwtValidator validator = new PlatformJwtValidator(
                    new PlatformJwtProperties(null,
                            "http://localhost:" + server.getAddress().getPort() + "/jwks",
                            null, null));
            String token = rs256Token(signingKey, 7L, "a@b.com", "customer",
                    Date.from(Instant.now().plusSeconds(3600)));
            assertThat(validator.validate(token)).isPresent();

            validator.expireJwksCacheForTests();
            long t0 = System.nanoTime();
            // Stale serve must return without waiting for the network.
            assertThat(validator.validate(token)).isPresent();
            assertThat((System.nanoTime() - t0) / 1_000_000).isLessThan(1000);
        } finally {
            server.stop(0);
        }
    }

    // ─── audit V-15: HMAC key built once; weak secret fails boot in strict profiles

    @Test
    void hmacSecretTooShortInStrictProfile_failsBootWithIllegalState() {
        assertThatThrownBy(() -> new PlatformJwtValidator(
                new PlatformJwtProperties("short-secret", null, null, null),
                PlatformJwtValidator.defaultRestClient(), true, null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("at least 32");
    }

    @Test
    void hmacSecretSufficientInStrictProfile_bootsAndValidates() throws Exception {
        // Must NOT throw: a 32+ byte secret is legitimate in prod/staging.
        PlatformJwtValidator validator = new PlatformJwtValidator(
                new PlatformJwtProperties(SECRET, null, null, null),
                PlatformJwtValidator.defaultRestClient(), true, null);

        String token = hs256Token(42L, "a@b.com", "customer",
                Date.from(Instant.now().plusSeconds(3600)));
        assertThat(validator.validate(token)).isPresent();
    }

}
