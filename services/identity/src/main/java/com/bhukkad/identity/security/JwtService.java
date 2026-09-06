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
 * the configured secret (plan §8: HMAC shared-secret before P8 JWKS hardening).
 */
@Service
@RequiredArgsConstructor
public class JwtService {

    private final JwtProperties properties;

    /**
     * Result of token introspection (RFC 7662). Returned as a 200 OK with
     * {@code valid: false} for invalid/expired tokens — never throws.
     */
    public record IntrospectionResult(boolean valid, Long customerId, String email, String scope, Instant expiresAt) {
    }

    public String issue(Long customerId, String email) {
        return issue(customerId, email, "customer");
    }

    /**
     * Issues a short-lived bearer access token (HS256). Validity comes from
     * {@code app.jwt.access-ttl-minutes} (15 min default); long-lived renewal
     * is handled by rotating refresh tokens, not by a fat access TTL.
     */
    public String issue(Long customerId, String email, String scope) {
        try {
            Instant now = Instant.now();
            JWTClaimsSet claims = new JWTClaimsSet.Builder()
                    .subject(String.valueOf(customerId))
                    .claim("email", email)
                    .claim("scope", scope != null ? scope : "customer")
                    .issueTime(Date.from(now))
                    .expirationTime(Date.from(now.plusSeconds(properties.accessTtlMinutes() * 60)))
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

    /**
     * Token introspection (RFC 7662 style): verifies signature and expiry and
     * returns the claims, or {@code valid: false} for any malformed/expired
     * token. Never throws — this is the backend for {@code /internal/verify}.
     */
    public IntrospectionResult introspect(String token) {
        try {
            SignedJWT jwt = SignedJWT.parse(token);
            if (!jwt.verify(new MACVerifier(properties.secret()))) {
                return new IntrospectionResult(false, null, null, null, null);
            }
            JWTClaimsSet claims = jwt.getJWTClaimsSet();
            Instant expiresAt = claims.getExpirationTime() == null ? null : claims.getExpirationTime().toInstant();
            if (expiresAt == null || expiresAt.isBefore(Instant.now())) {
                return new IntrospectionResult(false, null, null, null, null);
            }
            return new IntrospectionResult(
                    true,
                    Long.parseLong(claims.getSubject()),
                    claims.getStringClaim("email"),
                    claims.getStringClaim("scope"),
                    expiresAt);
        } catch (Exception e) {
            return new IntrospectionResult(false, null, null, null, null);
        }
    }
}
