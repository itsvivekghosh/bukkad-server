package com.bhukkad.common.security;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.MACSigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * P1 logout revocation: tokens minted BEFORE the user's revocation epoch are
 * rejected; after it (or within the ±2 s clock-slack grace) they still
 * validate. The check fails open for absent epochs, absent iat claims and
 * unwired revocation services.
 */
class PlatformJwtValidatorRevocationTest {

    private static final String SECRET = "0123456789abcdef0123456789abcdef"; // 32 bytes

    private static String hs256Token(long userId, Instant issuedAt) throws Exception {
        JWTClaimsSet claims = new JWTClaimsSet.Builder()
                .subject(String.valueOf(userId))
                .claim("email", "a@b.com")
                .claim("scope", "customer")
                .issueTime(issuedAt == null ? null : Date.from(issuedAt))
                .expirationTime(Date.from(Instant.now().plusSeconds(3600)))
                .build();
        SignedJWT jwt = new SignedJWT(new JWSHeader(JWSAlgorithm.HS256), claims);
        jwt.sign(new MACSigner(SECRET.getBytes(StandardCharsets.UTF_8)));
        return jwt.serialize();
    }

    private static JwtRevocationService epochAt(Instant epoch) {
        JwtRevocationService service = mock(JwtRevocationService.class);
        when(service.revocationEpoch(42L)).thenReturn(epoch == null ? Optional.empty() : Optional.of(epoch));
        return service;
    }

    private static final Instant NOW = Instant.now();

    @Test
    void tokenMintedBeforeEpoch_rejected() throws Exception {
        PlatformJwtValidator validator = new PlatformJwtValidator(
                new PlatformJwtProperties(SECRET, null, null, null),
                PlatformJwtValidator.defaultRestClient(), epochAt(NOW));

        String token = hs256Token(42L, NOW.minusSeconds(60));

        assertThat(validator.validate(token))
                .as("a pre-logout access token must not outlive the logout")
                .isEmpty();
    }

    @Test
    void tokenMintedAfterEpoch_accepted() throws Exception {
        PlatformJwtValidator validator = new PlatformJwtValidator(
                new PlatformJwtProperties(SECRET, null, null, null),
                PlatformJwtValidator.defaultRestClient(), epochAt(NOW.minusSeconds(60)));

        String token = hs256Token(42L, NOW);

        assertThat(validator.validate(token)).isPresent();
    }

    @Test
    void tokenMintedWithinClockSlackBeforeEpoch_accepted() throws Exception {
        // ±2 s grace: issuer/verifier clock drift must not log the user out.
        PlatformJwtValidator validator = new PlatformJwtValidator(
                new PlatformJwtProperties(SECRET, null, null, null),
                PlatformJwtValidator.defaultRestClient(), epochAt(NOW));

        String token = hs256Token(42L, NOW.minusSeconds(1));

        assertThat(validator.validate(token)).isPresent();
    }

    @Test
    void noEpochStored_accepted() throws Exception {
        PlatformJwtValidator validator = new PlatformJwtValidator(
                new PlatformJwtProperties(SECRET, null, null, null),
                PlatformJwtValidator.defaultRestClient(), epochAt(null));

        assertThat(validator.validate(hs256Token(42L, NOW.minusSeconds(3600)))).isPresent();
    }

    @Test
    void revocationServiceNotWired_checkSkipped() throws Exception {
        PlatformJwtValidator validator = new PlatformJwtValidator(
                new PlatformJwtProperties(SECRET, null, null, null));

        assertThat(validator.validate(hs256Token(42L, NOW.minusSeconds(3600)))).isPresent();
    }

    @Test
    void tokenWithoutIatClaim_failsOpen() throws Exception {
        PlatformJwtValidator validator = new PlatformJwtValidator(
                new PlatformJwtProperties(SECRET, null, null, null),
                PlatformJwtValidator.defaultRestClient(), epochAt(NOW));

        assertThat(validator.validate(hs256Token(42L, null)))
                .as("the epoch check is hardening on top of signature+expiry, never the sole gate")
                .isPresent();
    }

    @Test
    void otherUsersEpoch_doesNotAffectToken() throws Exception {
        JwtRevocationService service = mock(JwtRevocationService.class);
        when(service.revocationEpoch(43L)).thenReturn(Optional.of(NOW));
        PlatformJwtValidator validator = new PlatformJwtValidator(
                new PlatformJwtProperties(SECRET, null, null, null),
                PlatformJwtValidator.defaultRestClient(), service);

        assertThat(validator.validate(hs256Token(42L, NOW.minusSeconds(3600)))).isPresent();
    }
}
