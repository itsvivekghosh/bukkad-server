package com.bhukkad.identity.unit.service;

import com.bhukkad.identity.config.JwtProperties;
import com.bhukkad.identity.config.JwtService;
import com.bhukkad.identity.config.RsaKeyProperties;
import com.bhukkad.identity.config.RsaSigningKeys;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.MACSigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Date;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * W1-AUTH (ADR-004): identity issues RS256 access tokens with the full claim
 * set and still verifies legacy HS256 tokens during the grace window.
 */
class JwtServiceTest {

    private static final String SECRET = "0123456789abcdef0123456789abcdef"; // 32 bytes
    private static RsaSigningKeys rsaKeys;
    private static JwtService service;

    @BeforeAll
    static void setUp() {
        // Provision the real (auto-generated, persisted) key so the issuer and
        // verifier share one keypair — no mocks on the crypto path.
        rsaKeys = new RsaSigningKeys(new RsaKeyProperties(null, null, null, "w1-test-key"), false);
        service = new JwtService(new JwtProperties(SECRET, 60), rsaKeys);
    }

    @Test
    void issueAndVerify_roundTripsCustomerId() {
        String token = service.issue(42L, "a@b.com");
        assertThat(service.verifyAndGetCustomerId(token)).isEqualTo(42L);
    }

    @Test
    void issue_signsRs256WithKid() throws Exception {
        SignedJWT jwt = SignedJWT.parse(service.issue(42L, "a@b.com"));
        assertThat(jwt.getHeader().getAlgorithm()).isEqualTo(JWSAlgorithm.RS256);
        assertThat(jwt.getHeader().getKeyID()).isEqualTo("w1-test-key");
    }

    @Test
    void issue_carriesFullClaimSet() throws Exception {
        String token = service.issue(42L, "a@b.com", "ADMIN");
        JWTClaimsSet claims = SignedJWT.parse(token).getJWTClaimsSet();

        assertThat(claims.getSubject()).isEqualTo("42");
        assertThat(claims.getJWTID()).as("jti must be a UUID").isNotBlank();
        assertThat(claims.getIssuer()).isEqualTo(JwtService.ISSUER);
        assertThat(claims.getAudience()).containsExactly(JwtProperties.DEFAULT_AUDIENCE);
        assertThat(claims.getIssueTime()).isNotNull();
        assertThat(claims.getExpirationTime()).isNotNull();
        assertThat(claims.getClaim("scope")).isEqualTo("ADMIN");
        assertThat(claims.getClaim("role")).as("role claim mirrors scope").isEqualTo("ADMIN");
        assertThat(claims.getClaim("typ")).isEqualTo("access");
    }

    @Test
    void introspect_acceptsLegacyHs256DuringGrace() throws Exception {
        // A pre-cutover token signed HS256 with the shared secret: validators
        // must keep accepting it until the fleet-wide cutover completes.
        Instant now = Instant.now();
        JWTClaimsSet claims = new JWTClaimsSet.Builder()
                .subject("7")
                .claim("email", "legacy@b.com")
                .claim("scope", "customer")
                .jwtID(java.util.UUID.randomUUID().toString())
                .issueTime(Date.from(now))
                .expirationTime(Date.from(now.plusSeconds(600)))
                .build();
        SignedJWT legacy = new SignedJWT(new JWSHeader(JWSAlgorithm.HS256), claims);
        legacy.sign(new MACSigner(SECRET));

        var result = service.introspect(legacy.serialize());
        assertThat(result.valid()).isTrue();
        assertThat(result.customerId()).isEqualTo(7L);
        assertThat(result.scope()).isEqualTo("customer");
    }

    @Test
    void introspect_rejectsExpiredLegacyToken() throws Exception {
        Instant now = Instant.now();
        JWTClaimsSet claims = new JWTClaimsSet.Builder()
                .subject("7")
                .claim("scope", "customer")
                .issueTime(Date.from(now.minusSeconds(3600)))
                .expirationTime(Date.from(now.minusSeconds(600)))
                .build();
        SignedJWT legacy = new SignedJWT(new JWSHeader(JWSAlgorithm.HS256), claims);
        legacy.sign(new MACSigner(SECRET));

        assertThat(service.introspect(legacy.serialize()).valid()).isFalse();
    }

    @Test
    void introspect_rejectsAlgNone() {
        JWTClaimsSet claims = new JWTClaimsSet.Builder()
                .subject("7")
                .claim("scope", "admin")
                .expirationTime(Date.from(Instant.now().plusSeconds(600)))
                .build();
        com.nimbusds.jwt.PlainJWT plain = new com.nimbusds.jwt.PlainJWT(claims);

        assertThat(service.introspect(plain.serialize()).valid()).isFalse();
    }

    @Test
    void verify_tamperedToken_rejected() {
        String token = service.issue(42L, "a@b.com");
        String tampered = token.substring(0, token.length() - 4) + "AAAA";

        assertThatThrownBy(() -> service.verifyAndGetCustomerId(tampered))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void verify_garbageToken_rejected() {
        assertThatThrownBy(() -> service.verifyAndGetCustomerId("not-a-jwt"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void verify_emptyToken_rejected() {
        assertThatThrownBy(() -> service.verifyAndGetCustomerId(""))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void properties_rejectShortSecret() {
        assertThatThrownBy(() -> new JwtProperties("short", 60))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("secret");
    }

    @Test
    void properties_rejectNonPositiveTtl() {
        assertThatThrownBy(() -> new JwtProperties(SECRET, 0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("ttl");
    }

    @Test
    void properties_audienceDefaultsToBhukkadApi() {
        assertThat(new JwtProperties(SECRET, 60).audience()).isEqualTo("bhukkad-api");
        assertThat(new JwtProperties(SECRET, 60, 60, 30, "custom-aud").audience())
                .isEqualTo("custom-aud");
    }
}
