package com.bhukkad.common.security;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import reactor.blockhound.BlockHound;
import reactor.blockhound.BlockingOperationError;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * BlockHound proof (P1): once the JWKS cache is warm, {@link
 * PlatformJwtValidator#validate(String)} performs NO blocking call — it must
 * be safe to invoke from Reactor non-blocking threads (the gateway's edge
 * filters do exactly that via boundedElastic offloads). A blocking violation
 * on {@code Schedulers.parallel()} surfaces as a
 * {@link BlockingOperationError}.
 *
 * <p>The first test is a HARNESS GUARD: it proves BlockHound actually detects
 * a known blocking call in this JVM, so a green warm-path test cannot be the
 * result of a silently inert agent.</p>
 */
class PlatformJwtValidatorBlockHoundTest {

    private static final String SECRET = "0123456789abcdef0123456789abcdef"; // 32 bytes

    private static PlatformJwtValidator warmValidator;
    private static String warmToken;

    @BeforeAll
    static void installAndWarm() throws Exception {
        BlockHoundSupport.installOnce();

        // The warm-up's cold JWKS fetch (blocking HTTP) runs on the MAIN test
        // thread, which BlockHound allows; the warm assertions below run on
        // the non-blocking parallel scheduler.
        com.sun.net.httpserver.HttpServer server = com.sun.net.httpserver.HttpServer.create(
                new java.net.InetSocketAddress(0), 0);
        RSAKeyHolder holder = new RSAKeyHolder();
        byte[] body = holder.jwksJson.getBytes(StandardCharsets.UTF_8);
        server.createContext("/jwks", exchange -> {
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            try (var os = exchange.getResponseBody()) {
                os.write(body);
            }
        });
        server.start();
        try {
            warmValidator = new PlatformJwtValidator(
                    new PlatformJwtProperties(null,
                            "http://localhost:" + server.getAddress().getPort() + "/jwks",
                            null, null));
            warmToken = holder.token(77L, "warm@b.com", "customer",
                    Date.from(Instant.now().plusSeconds(3600)));
            assertThat(warmValidator.validate(warmToken)).isPresent();
        } finally {
            server.stop(0);
        }
    }

    @Test
    void harnessGuard_blockHoundDetectsBlockingCallOnNonBlockingThread() {
        reactor.test.StepVerifier.create(Mono.fromCallable(() -> {
                    Thread.sleep(20); // known blocking call on a non-blocking thread
                    return 1;
                }).subscribeOn(Schedulers.parallel()))
                .expectError(BlockingOperationError.class)
                .verify();
    }

    @Test
    void warmJwksPath_validate_neverBlocks() {
        reactor.test.StepVerifier.create(
                        Mono.fromCallable(() -> warmValidator.validate(warmToken))
                                .subscribeOn(Schedulers.parallel()))
                .assertNext(principal -> assertThat(principal).isPresent())
                .verifyComplete();
    }

    @Test
    void hs256WarmPath_validate_neverBlocks() throws Exception {
        // HMAC verification path (no JWKS cache): pure CPU, must also be
        // non-blocking.
        PlatformJwtValidator hmacValidator = new PlatformJwtValidator(
                new PlatformJwtProperties(SECRET, null, null, null));
        var claims = new com.nimbusds.jwt.JWTClaimsSet.Builder()
                .subject("78")
                .claim("email", "hmac@b.com")
                .claim("scope", "customer")
                .issueTime(Date.from(Instant.now()))
                .expirationTime(Date.from(Instant.now().plusSeconds(3600)))
                .build();
        var jwt = new com.nimbusds.jwt.SignedJWT(
                new com.nimbusds.jose.JWSHeader(com.nimbusds.jose.JWSAlgorithm.HS256), claims);
        jwt.sign(new com.nimbusds.jose.crypto.MACSigner(SECRET.getBytes(StandardCharsets.UTF_8)));
        String token = jwt.serialize();
        assertThat(hmacValidator.validate(token)).isPresent();

        reactor.test.StepVerifier.create(
                        Mono.fromCallable(() -> hmacValidator.validate(token))
                                .subscribeOn(Schedulers.parallel()))
                .assertNext(principal -> assertThat(principal).isPresent())
                .verifyComplete();
    }

    /** Local RS256 key + JWKS document for the warm-up. */
    private static final class RSAKeyHolder {
        final com.nimbusds.jose.jwk.RSAKey signingKey;
        final String jwksJson;

        RSAKeyHolder() throws Exception {
            this.signingKey = new com.nimbusds.jose.jwk.gen.RSAKeyGenerator(2048).keyID("key-1").generate();
            this.jwksJson = new com.nimbusds.jose.jwk.JWKSet(signingKey.toPublicJWK()).toString();
        }

        String token(long userId, String email, String scope, Date expiry) throws Exception {
            var header = new com.nimbusds.jose.JWSHeader.Builder(com.nimbusds.jose.JWSAlgorithm.RS256)
                    .type(com.nimbusds.jose.JOSEObjectType.JWT)
                    .keyID(signingKey.getKeyID())
                    .build();
            var jwtClaims = new com.nimbusds.jwt.JWTClaimsSet.Builder()
                    .subject(String.valueOf(userId))
                    .claim("email", email)
                    .claim("scope", scope)
                    .issueTime(new Date())
                    .expirationTime(expiry)
                    .build();
            var signed = new com.nimbusds.jwt.SignedJWT(header, jwtClaims);
            signed.sign(new com.nimbusds.jose.crypto.RSASSASigner(signingKey));
            return signed.serialize();
        }
    }
}
