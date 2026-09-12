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
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

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
                PlatformJwtValidator.defaultRestClient(), true, null, null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("at least 32");
    }

    @Test
    void hmacSecretSufficientInStrictProfile_bootsAndValidates() throws Exception {
        // Must NOT throw: a 32+ byte secret is legitimate in prod/staging.
        PlatformJwtValidator validator = new PlatformJwtValidator(
                new PlatformJwtProperties(SECRET, null, null, null),
                PlatformJwtValidator.defaultRestClient(), true, null, null);

        String token = hs256Token(42L, "a@b.com", "customer",
                Date.from(Instant.now().plusSeconds(3600)));
        assertThat(validator.validate(token)).isPresent();
    }

    // ─── ADR-004: RS256+JWKS cutover, dual-key HMAC grace window ───────────────

    @Test
    void dualMode_legacyHs256Token_acceptedDuringGrace() throws Exception {
        RSAKey signingKey = new RSAKeyGenerator(2048).keyID("key-1").generate();
        String jwksJson = new JWKSet(signingKey.toPublicJWK()).toString();

        HttpServer server = jwksServer(jwksJson);
        try {
            // Both JWKS and HMAC secret configured (the cutover posture).
            PlatformJwtValidator validator = new PlatformJwtValidator(
                    new PlatformJwtProperties(SECRET,
                            "http://localhost:" + server.getAddress().getPort() + "/jwks",
                            null, null, true));

            String legacy = hs256Token(5L, "legacy@b.com", "customer",
                    Date.from(Instant.now().plusSeconds(3600)));
            Optional<TokenPrincipal> principal = validator.validate(legacy);
            assertThat(principal).as("legacy HS256 token must verify during the grace window")
                    .isPresent();
            assertThat(principal.get().userId()).isEqualTo(5L);
        } finally {
            server.stop(0);
        }
    }

    @Test
    void dualMode_hmacGraceDisabled_hs256Rejected_rs256Accepted() throws Exception {
        RSAKey signingKey = new RSAKeyGenerator(2048).keyID("key-1").generate();
        String jwksJson = new JWKSet(signingKey.toPublicJWK()).toString();

        HttpServer server = jwksServer(jwksJson);
        try {
            PlatformJwtValidator validator = new PlatformJwtValidator(
                    new PlatformJwtProperties(SECRET,
                            "http://localhost:" + server.getAddress().getPort() + "/jwks",
                            null, null, false));

            String legacy = hs256Token(5L, "legacy@b.com", "customer",
                    Date.from(Instant.now().plusSeconds(3600)));
            assertThat(validator.validate(legacy))
                    .as("after the grace flag flips, HS256 tokens are rejected")
                    .isEmpty();

            String modern = rs256Token(signingKey, 6L, "modern@b.com", "customer",
                    Date.from(Instant.now().plusSeconds(3600)));
            assertThat(validator.validate(modern))
                    .as("RS256 tokens keep working after the grace flag flips")
                    .isPresent();
        } finally {
            server.stop(0);
        }
    }

    @Test
    void rs256Token_withoutJwksUrl_rejected() throws Exception {
        // An RS256 token cannot be trusted when the validator has no key source.
        RSAKey signingKey = new RSAKeyGenerator(2048).keyID("key-1").generate();
        PlatformJwtValidator validator = new PlatformJwtValidator(
                new PlatformJwtProperties(SECRET, null, null, null));

        String token = rs256Token(signingKey, 7L, "a@b.com", "customer",
                Date.from(Instant.now().plusSeconds(3600)));
        assertThat(validator.validate(token)).isEmpty();
    }

    @Test
    void algNoneToken_rejected() {
        // Unsigned (alg=none) JWT — must never authenticate.
        JWTClaimsSet claims = new JWTClaimsSet.Builder()
                .subject("9")
                .claim("email", "none@b.com")
                .claim("scope", "admin")
                .issueTime(Date.from(Instant.now()))
                .expirationTime(Date.from(Instant.now().plusSeconds(3600)))
                .build();
        com.nimbusds.jwt.PlainJWT plain = new com.nimbusds.jwt.PlainJWT(claims);

        PlatformJwtValidator validator = new PlatformJwtValidator(
                new PlatformJwtProperties(SECRET, null, null, null));
        assertThat(validator.validate(plain.serialize())).isEmpty();
    }

    @Test
    void hs384DowngradeToken_rejected() throws Exception {
        // V-15 verification matrix: HS384 is an algorithm downgrade → reject.
        JWTClaimsSet claims = new JWTClaimsSet.Builder()
                .subject("9")
                .claim("email", "hs384@b.com")
                .claim("scope", "customer")
                .issueTime(Date.from(Instant.now()))
                .expirationTime(Date.from(Instant.now().plusSeconds(3600)))
                .build();
        // A 32-byte secret is valid for HS384 too — Nimbus's MACSigner only
        // accepts HS256, so build the compact JWS by hand (base64url header
        // declaring HS384 + payload + HMAC-SHA384 signature) and require the
        // validator to reject the downgrade attempt.
        java.util.Base64.Encoder url = java.util.Base64.getUrlEncoder().withoutPadding();
        String header = url.encodeToString(
                "{\"alg\":\"HS384\"}".getBytes(StandardCharsets.UTF_8));
        String payload = url.encodeToString(claims.toJSONObject().toString()
                .getBytes(StandardCharsets.UTF_8));
        javax.crypto.Mac mac = javax.crypto.Mac.getInstance("HmacSHA384");
        mac.init(new javax.crypto.spec.SecretKeySpec(
                SECRET.getBytes(StandardCharsets.UTF_8), "HmacSHA384"));
        byte[] sig = mac.doFinal((header + "." + payload).getBytes(StandardCharsets.US_ASCII));
        String forged = header + "." + payload + "." + url.encodeToString(sig);

        PlatformJwtValidator validator = new PlatformJwtValidator(
                new PlatformJwtProperties(SECRET, null, null, null));
        assertThat(validator.validate(forged)).isEmpty();
    }

    @Test
    void hs256Token_withoutSharedSecret_rejected() throws Exception {
        // Downgrade guard: an HS256 token when only JWKS is configured (no
        // secret) must be rejected, not silently trusted.
        RSAKey signingKey = new RSAKeyGenerator(2048).keyID("key-1").generate();
        String jwksJson = new JWKSet(signingKey.toPublicJWK()).toString();

        HttpServer server = jwksServer(jwksJson);
        try {
            PlatformJwtValidator validator = new PlatformJwtValidator(
                    new PlatformJwtProperties(null,
                            "http://localhost:" + server.getAddress().getPort() + "/jwks",
                            null, null));

            String token = hs256Token(8L, "nosecret@b.com", "customer",
                    Date.from(Instant.now().plusSeconds(3600)));
            assertThat(validator.validate(token)).isEmpty();
        } finally {
            server.stop(0);
        }
    }

    @Test
    void jwksMode_wrongIssuer_rejected() throws Exception {
        RSAKey signingKey = new RSAKeyGenerator(2048).keyID("key-1").generate();
        HttpServer server = jwksServer(new JWKSet(signingKey.toPublicJWK()).toString());
        try {
            PlatformJwtValidator validator = new PlatformJwtValidator(
                    new PlatformJwtProperties(null,
                            "http://localhost:" + server.getAddress().getPort() + "/jwks",
                            "bhukkad-identity", null));

            JWTClaimsSet claims = new JWTClaimsSet.Builder()
                    .subject("11")
                    .issuer("evil-issuer")
                    .claim("email", "iss@b.com")
                    .claim("scope", "customer")
                    .issueTime(Date.from(Instant.now()))
                    .expirationTime(Date.from(Instant.now().plusSeconds(3600)))
                    .build();
            SignedJWT jwt = new SignedJWT(rs256Header(signingKey), claims);
            jwt.sign(new RSASSASigner(signingKey));

            assertThat(validator.validate(jwt.serialize())).isEmpty();
        } finally {
            server.stop(0);
        }
    }

    @Test
    void jwksMode_wrongAudience_rejected() throws Exception {
        RSAKey signingKey = new RSAKeyGenerator(2048).keyID("key-1").generate();
        HttpServer server = jwksServer(new JWKSet(signingKey.toPublicJWK()).toString());
        try {
            PlatformJwtValidator validator = new PlatformJwtValidator(
                    new PlatformJwtProperties(null,
                            "http://localhost:" + server.getAddress().getPort() + "/jwks",
                            null, "bhukkad-api"));

            JWTClaimsSet claims = new JWTClaimsSet.Builder()
                    .subject("12")
                    .audience("other-service")
                    .claim("email", "aud@b.com")
                    .claim("scope", "customer")
                    .issueTime(Date.from(Instant.now()))
                    .expirationTime(Date.from(Instant.now().plusSeconds(3600)))
                    .build();
            SignedJWT jwt = new SignedJWT(rs256Header(signingKey), claims);
            jwt.sign(new RSASSASigner(signingKey));

            assertThat(validator.validate(jwt.serialize())).isEmpty();
        } finally {
            server.stop(0);
        }
    }

    @Test
    void jwksMode_forgedSignatureByUnknownKey_rejected() throws Exception {
        RSAKey trustedKey = new RSAKeyGenerator(2048).keyID("key-1").generate();
        RSAKey attackerKey = new RSAKeyGenerator(2048).keyID("key-1").generate(); // same kid, wrong key
        HttpServer server = jwksServer(new JWKSet(trustedKey.toPublicJWK()).toString());
        try {
            PlatformJwtValidator validator = new PlatformJwtValidator(
                    new PlatformJwtProperties(null,
                            "http://localhost:" + server.getAddress().getPort() + "/jwks",
                            null, null));

            String forged = rs256Token(attackerKey, 13L, "forge@b.com", "admin",
                    Date.from(Instant.now().plusSeconds(3600)));
            assertThat(validator.validate(forged)).isEmpty();
        } finally {
            server.stop(0);
        }
    }

    @Test
    void roleClaimFallback_usedWhenScopeAbsent() throws Exception {
        // ADR-004: tokens may carry only the new `role` claim.
        JWTClaimsSet claims = new JWTClaimsSet.Builder()
                .subject("14")
                .claim("email", "role@b.com")
                .claim("role", "ADMIN")
                .issueTime(Date.from(Instant.now()))
                .expirationTime(Date.from(Instant.now().plusSeconds(3600)))
                .build();
        SignedJWT jwt = new SignedJWT(new JWSHeader(JWSAlgorithm.HS256), claims);
        jwt.sign(new MACSigner(SECRET));

        PlatformJwtValidator validator = new PlatformJwtValidator(
                new PlatformJwtProperties(SECRET, null, null, null));
        Optional<TokenPrincipal> principal = validator.validate(jwt.serialize());
        assertThat(principal).isPresent();
        assertThat(principal.get().scope()).isEqualTo("ADMIN");
    }

    private static HttpServer jwksServer(String jwksJson) throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/jwks", exchange -> {
            byte[] body = jwksJson.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(body);
            }
        });
        server.start();
        return server;
    }

    private static JWSHeader rs256Header(RSAKey signingKey) {
        return new JWSHeader.Builder(JWSAlgorithm.RS256)
                .type(JOSEObjectType.JWT)
                .keyID(signingKey.getKeyID())
                .build();
    }

    // ─── P1 REVOCATION: logout/credential-epoch kills live access tokens ────────

    /** Stub whose revokedBefore() answers a canned value / failure per case. */
    private static JwtRevocationService revocationStub(java.util.function.LongSupplier epoch) {
        JwtRevocationService stub = mock(JwtRevocationService.class);
        when(stub.revokedBefore(anyLong())).thenAnswer(invocation -> {
            long result = epoch.getAsLong();
            return result == 0L ? null : Instant.ofEpochSecond(result);
        });
        return stub;
    }

    private static long epoch(Instant instant) {
        return instant.getEpochSecond();
    }

    private static PlatformJwtValidator validatorWith(io.micrometer.core.instrument.MeterRegistry meters,
                                                      JwtRevocationService revocations) {
        return new PlatformJwtValidator(new PlatformJwtProperties(SECRET, null, null, null),
                PlatformJwtValidator.defaultRestClient(), false, meters, revocations);
    }

    @Test
    void accessToken_issuedBeforeRevocationEpoch_rejected() throws Exception {
        JwtRevocationService revocations = revocationStub(
                () -> epoch(Instant.now().plusSeconds(30))); // epoch AHEAD of iat
        PlatformJwtValidator validator = validatorWith(new io.micrometer.core.instrument.simple.SimpleMeterRegistry(),
                revocations);

        String token = hs256Token(42L, "a@b.com", "customer",
                Date.from(Instant.now().plusSeconds(3600)));

        assertThat(validator.validate(token))
                .as("token predating the logout epoch must die with it")
                .isEmpty();
    }

    @Test
    void accessToken_issuedBeforeEpoch_recordsRejectedCounter() throws Exception {
        io.micrometer.core.instrument.simple.SimpleMeterRegistry meters =
                new io.micrometer.core.instrument.simple.SimpleMeterRegistry();
        PlatformJwtValidator validator = validatorWith(meters,
                revocationStub(() -> epoch(Instant.now().plusSeconds(30))));

        validator.validate(hs256Token(42L, "a@b.com", "customer",
                Date.from(Instant.now().plusSeconds(3600))));

        assertThat(meters.get("service_auth_rejected").tag("reason", "revoked").counter().count())
                .isEqualTo(1.0);
    }

    @Test
    void accessToken_issuedAfterEpoch_accepted() throws Exception {
        PlatformJwtValidator validator = validatorWith(null,
                revocationStub(() -> epoch(Instant.now().minusSeconds(3600))));

        String token = hs256Token(42L, "a@b.com", "customer",
                Date.from(Instant.now().plusSeconds(3600)));

        assertThat(validator.validate(token))
                .as("a fresh (post-logout-login) token must verify")
                .isPresent();
    }

    @Test
    void accessToken_withoutEpoch_alwaysAccepted() throws Exception {
        PlatformJwtValidator validator = validatorWith(null, revocationStub(() -> 0L));

        assertThat(validator.validate(hs256Token(42L, "a@b.com", "customer",
                Date.from(Instant.now().plusSeconds(3600))))).isPresent();
    }

    @Test
    void accessToken_withoutIatClaim_failsOpenOnEpochCheck() throws Exception {
        // The epoch check is hardening on top of signature+expiry, never the
        // sole gate: no iat claim => nothing to compare, token still verifies.
        JWTClaimsSet claims = new JWTClaimsSet.Builder()
                .subject("42")
                .claim("email", "a@b.com")
                .claim("scope", "customer")
                .expirationTime(Date.from(Instant.now().plusSeconds(3600)))
                .build();
        SignedJWT jwt = new SignedJWT(new JWSHeader(JWSAlgorithm.HS256), claims);
        jwt.sign(new MACSigner(SECRET));

        PlatformJwtValidator validator = validatorWith(null,
                revocationStub(() -> epoch(Instant.now().plusSeconds(30))));

        assertThat(validator.validate(jwt.serialize())).isPresent();
    }

    @Test
    void redisFailure_duringRevocationCheck_failsOpenWithBypassCounter() throws Exception {
        io.micrometer.core.instrument.simple.SimpleMeterRegistry meters =
                new io.micrometer.core.instrument.simple.SimpleMeterRegistry();
        JwtRevocationService failing = mock(JwtRevocationService.class);
        when(failing.revokedBefore(anyLong())).thenThrow(new RuntimeException("connection refused"));
        PlatformJwtValidator validator = validatorWith(meters, failing);

        String token = hs256Token(42L, "a@b.com", "customer",
                Date.from(Instant.now().plusSeconds(3600)));

        assertThat(validator.validate(token))
                .as("availability wins: a Redis outage must not 401 the fleet")
                .isPresent();
        assertThat(meters.get(PlatformJwtValidator.METRIC_REVOCATION_BYPASS).counter().count())
                .isEqualTo(1.0);
    }

    @Test
    void absentRevocationService_behavesExactlyLikePreP1() throws Exception {
        // The 4-arg constructor used across every existing test slice has no
        // revocation store: validation must be untouched and never NPE.
        PlatformJwtValidator validator = new PlatformJwtValidator(
                new PlatformJwtProperties(SECRET, null, null, null),
                PlatformJwtValidator.defaultRestClient(), false, null);

        assertThat(validator.validate(hs256Token(42L, "a@b.com", "customer",
                Date.from(Instant.now().plusSeconds(3600))))).isPresent();
    }

    @Test
    void tokenWithoutIssuedAt_skipsEpochComparisonButStillVerifies() throws Exception {
        JwtRevocationService revocations = revocationStub(() -> epoch(Instant.now()));
        PlatformJwtValidator validator = validatorWith(null, revocations);

        JWTClaimsSet claims = new JWTClaimsSet.Builder()
                .subject("77")
                .claim("email", "no-iat@b.com")
                .claim("scope", "customer")
                .expirationTime(Date.from(Instant.now().plusSeconds(3600)))
                .build();
        SignedJWT jwt = new SignedJWT(new JWSHeader(JWSAlgorithm.HS256), claims);
        jwt.sign(new MACSigner(SECRET));

        // No iat → nothing to order against the epoch; signature/claims still rule.
        assertThat(validator.validate(jwt.serialize())).isPresent();
    }

}
