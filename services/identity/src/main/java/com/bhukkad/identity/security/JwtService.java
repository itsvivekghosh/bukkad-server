package com.bhukkad.identity.security;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.MACSigner;
import com.nimbusds.jose.crypto.MACVerifier;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.Date;

/**
 * Issues and verifies signed JWTs for authenticated customers. Uses HS256 with
 * the configured secret (plan §8: HS512 shared-secret only intra-monolith; a
 * per-service JWKS pair is the P8 hardening step).
 */
@Service
@RequiredArgsConstructor
public class JwtService {

    private final JwtProperties properties;

    public String issue(Long customerId, String email) {
        try {
            Instant now = Instant.now();
            JWTClaimsSet claims = new JWTClaimsSet.Builder()
                    .subject(String.valueOf(customerId))
                    .claim("email", email)
                    .claim("scope", "customer")
                    .issueTime(Date.from(now))
                    .expirationTime(Date.from(now.plusSeconds(properties.ttlMinutes() * 60)))
                    .build();
            SignedJWT jwt = new SignedJWT(new JWSHeader(JWSAlgorithm.HS256), claims);
            jwt.sign(new MACSigner(properties.secret()));
            return jwt.serialize();
        } catch (Exception e) {
            throw new IllegalStateException("Failed to sign JWT", e);
        }
    }

    /**
     * Verifies signature and expiry, returning the subject (customerId).
     *
     * @throws IllegalArgumentException when the token is invalid or expired
     */
    public long verifyAndGetCustomerId(String token) {
        try {
            SignedJWT jwt = SignedJWT.parse(token);
            if (!jwt.verify(new MACVerifier(properties.secret()))) {
                throw new IllegalArgumentException("JWT signature invalid");
            }
            JWTClaimsSet claims = jwt.getJWTClaimsSet();
            if (claims.getExpirationTime() == null
                    || claims.getExpirationTime().toInstant().isBefore(Instant.now())) {
                throw new IllegalArgumentException("JWT expired");
            }
            return Long.parseLong(claims.getSubject());
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalArgumentException("JWT invalid", e);
        }
    }
}
