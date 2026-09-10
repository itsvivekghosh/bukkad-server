package com.bhukkad.identity.api;

import com.bhukkad.common.security.PlatformJwtProperties;
import com.bhukkad.common.security.PlatformJwtValidator;
import com.bhukkad.common.security.TokenPrincipal;
import com.bhukkad.identity.AbstractIdentityPostgresTest;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.MACSigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.web.client.RestClient;

import java.security.interfaces.RSAPublicKey;
import java.time.Instant;
import java.util.Date;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * W1-AUTH deliverable 1+2: JWKS round-trip. Identity issues RS256, serves
 * public keys at /.well-known/jwks.json, and the platform validator accepts
 * the token via the JWKS path. Legacy HS256 still verifies during the grace
 * window; after the grace flag flips it is rejected. Claims matrix: iss/aud
 * enforced when the validator is configured.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class IdentityJwksIntegrationTest extends AbstractIdentityPostgresTest {

    @LocalServerPort
    private int port;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private com.bhukkad.identity.security.JwtService jwtService;

    private RestClient client() {
        return RestClient.builder().baseUrl("http://localhost:" + port).build();
    }

    private String registerAndGetToken(String email) throws Exception {
        var register = client().post().uri("/api/v1/auth/register")
                .header("Content-Type", "application/json")
                .body("{\"email\":\"" + email + "\",\"phoneNumber\":\"999\","
                        + "\"fullName\":\"JWKS IT\",\"password\":\"password123\"}")
                .retrieve()
                .toEntity(String.class);
        assertThat(register.getStatusCode().is2xxSuccessful()).isTrue();
        return objectMapper.readTree(register.getBody()).get("token").asText();
    }

    @Test
    void jwksEndpoint_servesPublicKeysWithCacheHeaders() throws Exception {
        var response = client().get().uri("/.well-known/jwks.json").retrieve().toEntity(String.class);
        assertThat(response.getStatusCode().is2xxSuccessful()).isTrue();
        assertThat(response.getHeaders().getCacheControl()).contains("max-age=3600");

        JsonNode json = objectMapper.readTree(response.getBody());
        assertThat(json.get("keys")).isNotNull();
        assertThat(json.get("keys").size()).isEqualTo(1);
        JsonNode key = json.get("keys").get(0);
        assertThat(key.get("kty").asText()).isEqualTo("RSA");
        assertThat(key.get("kid").asText()).isEqualTo(TEST_KEY_ID);
        assertThat(key.has("d")).as("private exponent must never be served").isFalse();
        assertThat(key.has("p")).isFalse();
        assertThat(key.has("q")).isFalse();
    }

    @Test
    void rs256IssuedToken_verifiesViaLiveJwksEndpoint() throws Exception {
        String token = registerAndGetToken("jwks-roundtrip@b.com");

        // The validator fetches identity's LIVE JWKS endpoint (zero-coordination
        // ADR-004 contract) and accepts the token through the JWKS path.
        PlatformJwtValidator validator = new PlatformJwtValidator(
                new PlatformJwtProperties(null, "http://localhost:" + port + "/.well-known/jwks.json",
                        null, null));

        Optional<TokenPrincipal> principal = validator.validate(token);
        assertThat(principal).as("RS256 token must verify via the served JWKS").isPresent();
        assertThat(principal.get().scope()).isEqualTo("CUSTOMER");
    }

    @Test
    void issuedToken_carriesIssuerAudienceAndJti() throws Exception {
        String token = registerAndGetToken("claims@b.com");
        SignedJWT jwt = SignedJWT.parse(token);
        var claims = jwt.getJWTClaimsSet();

        assertThat(jwt.getHeader().getAlgorithm()).isEqualTo(JWSAlgorithm.RS256);
        assertThat(jwt.getHeader().getKeyID()).isEqualTo(TEST_KEY_ID);
        assertThat(claims.getIssuer()).isEqualTo("bhukkad-identity");
        assertThat(claims.getAudience()).containsExactly("bhukkad-api");
        assertThat(claims.getJWTID()).isNotBlank();
        assertThat(claims.getClaim("role")).isEqualTo("CUSTOMER");
        assertThat(claims.getClaim("scope")).isEqualTo("CUSTOMER");
        assertThat(claims.getExpirationTime()).isNotNull();
    }

    @Test
    void legacyHs256Token_acceptedDuringGrace() throws Exception {
        PlatformJwtValidator validator = new PlatformJwtValidator(
                new PlatformJwtProperties("0123456789abcdef0123456789abcdef",
                        "http://localhost:" + port + "/.well-known/jwks.json", null, null, true));

        Instant now = Instant.now();
        JWTClaimsSet claims = new JWTClaimsSet.Builder()
                .subject("55")
                .claim("email", "legacy@b.com")
                .claim("scope", "customer")
                .issueTime(Date.from(now))
                .expirationTime(Date.from(now.plusSeconds(600)))
                .build();
        SignedJWT legacy = new SignedJWT(new JWSHeader(JWSAlgorithm.HS256), claims);
        legacy.sign(new MACSigner("0123456789abcdef0123456789abcdef"));

        assertThat(validator.validate(legacy.serialize()))
                .as("grace window: legacy HS256 tokens keep verifying")
                .isPresent();
    }

    @Test
    void legacyHs256Token_rejectedAfterGraceFlip() throws Exception {
        PlatformJwtValidator validator = new PlatformJwtValidator(
                new PlatformJwtProperties("0123456789abcdef0123456789abcdef",
                        "http://localhost:" + port + "/.well-known/jwks.json", null, null, false));

        Instant now = Instant.now();
        JWTClaimsSet claims = new JWTClaimsSet.Builder()
                .subject("55")
                .claim("scope", "customer")
                .issueTime(Date.from(now))
                .expirationTime(Date.from(now.plusSeconds(600)))
                .build();
        SignedJWT legacy = new SignedJWT(new JWSHeader(JWSAlgorithm.HS256), claims);
        legacy.sign(new MACSigner("0123456789abcdef0123456789abcdef"));

        assertThat(validator.validate(legacy.serialize()))
                .as("grace flipped: HS256 tokens are rejected")
                .isEmpty();
    }

    @Test
    void rs256Token_wrongAudience_rejectedByConfiguredValidator() throws Exception {
        String token = registerAndGetToken("wrongaud@b.com");
        PlatformJwtValidator validator = new PlatformJwtValidator(
                new PlatformJwtProperties(null, "http://localhost:" + port + "/.well-known/jwks.json",
                        null, "other-audience"));

        assertThat(validator.validate(token)).isEmpty();
    }

    @Test
    void issuedToken_verifiesViaLocalKeypair_directly() throws Exception {
        long customerId = 123L;
        String token = jwtService.issue(customerId, "direct@b.com", "ADMIN");
        assertThat(jwtService.verifyAndGetCustomerId(token)).isEqualTo(customerId);
        var result = jwtService.introspect(token);
        assertThat(result.valid()).isTrue();
        assertThat(result.scope()).isEqualTo("ADMIN");
        assertThat(TEST_RSA_KEY.toRSAPublicKey()).isInstanceOf(RSAPublicKey.class);
    }
}
