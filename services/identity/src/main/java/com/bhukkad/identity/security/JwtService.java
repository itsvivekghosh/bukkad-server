package com.bhukkad.identity.security;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.MACVerifier;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.crypto.RSASSAVerifier;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.Date;
import java.util.UUID;

/**
 * Issues and verifies signed JWTs for authenticated customers.
 *
 * <p>ADR-004 step 1: access tokens are signed with <b>RS256</b> (identity's
 * RSA keypair + {@code kid} header) and carry the full claim set —
 * {@code jti} (UUID), {@code iss} (bhukkad-identity), {@code aud},
 * {@code iat}/{@code exp} and {@code role} alongside the legacy {@code scope}
 * for back-compat. Verification still accepts legacy HS256 tokens signed with
 * the shared secret during the cutover grace window (issue + introspect both
 * go through this service, so mesh callers see one consistent surface).</p>
 */
@Service
public class JwtService {

    public static final String ISSUER = "bhukkad-identity";
    public static final String DEFAULT_AUDIENCE = JwtProperties.DEFAULT_AUDIENCE;

    private final JwtProperties properties;
    private final RsaSigningKeys rsaKeys;

    /**
     * Result of token introspection (RFC 7662). Returned as a 200 OK with
     * {@code valid: false} for invalid/expired tokens — never throws.
     */
    public record IntrospectionResult(boolean valid, Long customerId, String email, String scope, Instant expiresAt) {
    }

    public JwtService(JwtProperties properties, RsaSigningKeys rsaKeys) {
        this.properties = properties;
        this.rsaKeys = rsaKeys;
    }

    public String issue(Long customerId, String email) {
        return issue(customerId, email, "customer");
    }

    /**
     * Issues a short-lived RS256 bearer access token. Validity comes from
     * {@code app.jwt.access-ttl-minutes} (15 min default); long-lived renewal
     * is handled by rotating refresh tokens, not by a fat access TTL.
     * Claims: {@code sub}/{@code email}/{@code scope} + {@code role},
     * {@code jti} (UUID), {@code iss}, {@code aud}, {@code iat}, {@code exp}
     * and {@code typ:"access"}.
     */
    public String issue(Long customerId, String email, String scope) {
        Instant now = Instant.now();
        Instant expiry = now.plusSeconds(properties.accessTtlMinutes() * 60);
        JWTClaimsSet claims = new JWTClaimsSet.Builder()
                .subject(String.valueOf(customerId))
                .issuer(ISSUER)
                .audience(properties.audience())
                .jwtID(UUID.randomUUID().toString())
                .claim("email", email)
                .claim("scope", scope != null ? scope : "customer")
                .claim("role", scope != null ? scope : "customer")
                .claim("typ", "access")
                .issueTime(Date.from(now))
                .expirationTime(Date.from(expiry))
                .build();
        try {
            SignedJWT jwt = new SignedJWT(rs256Header(), claims);
            jwt.sign(new RSASSASigner(rsaKeys.signingKey()));
            return jwt.serialize();
        } catch (JOSEException e) {
            throw new IllegalStateException("Failed to sign RS256 JWT", e);
        }
    }

    private JWSHeader rs256Header() {
        return new JWSHeader.Builder(JWSAlgorithm.RS256)
                .keyID(rsaKeys.signingKey().getKeyID())
                .build();
    }

    /**
     * Verifies signature (RS256 via the local keypair, or legacy HS256 via
     * the shared secret during the grace window) and expiry, returning the
     * subject (customerId).
     *
     * @throws IllegalArgumentException when the token is invalid or expired
     */
    public long verifyAndGetCustomerId(String token) {
        try {
            JWTClaimsSet claims = verifiedClaims(token);
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
     * Verifies both the new RS256 tokens and legacy HS256 tokens so callers
     * still holding pre-cutover tokens keep working through the grace window.
     */
    public IntrospectionResult introspect(String token) {
        try {
            JWTClaimsSet claims = verifiedClaims(token);
            Instant expiresAt = claims.getExpirationTime() == null ? null : claims.getExpirationTime().toInstant();
            if (expiresAt == null || expiresAt.isBefore(Instant.now())) {
                return new IntrospectionResult(false, null, null, null, null);
            }
            String scope = claims.getStringClaim("scope");
            if (scope == null) {
                scope = claims.getStringClaim("role");
            }
            return new IntrospectionResult(
                    true,
                    Long.parseLong(claims.getSubject()),
                    claims.getStringClaim("email"),
                    scope,
                    expiresAt);
        } catch (Exception e) {
            return new IntrospectionResult(false, null, null, null, null);
        }
    }

    /**
     * Signature check for both cutover key types. {@code alg=none} and any
     * algorithm other than RS256/HS256 are rejected (downgrade guard). The
     * HMAC verifier is built once — the secret is validated ≥32 chars by
     * {@link JwtProperties} (audit V-15: never per-request key construction).
     */
    private JWTClaimsSet verifiedClaims(String token) throws Exception {
        SignedJWT jwt = SignedJWT.parse(token);
        String algName = jwt.getHeader().getAlgorithm() == null
                ? "" : jwt.getHeader().getAlgorithm().getName();
        if (JWSAlgorithm.RS256.getName().equals(algName)) {
            if (!jwt.verify(new RSASSAVerifier(rsaKeys.signingKey().toRSAPublicKey()))) {
                throw new IllegalArgumentException("JWT signature invalid");
            }
        } else if (JWSAlgorithm.HS256.getName().equals(algName)) {
            if (!jwt.verify(new MACVerifier(properties.secret()))) {
                throw new IllegalArgumentException("JWT signature invalid");
            }
        } else {
            throw new IllegalArgumentException("Unsupported JWT alg: " + algName);
        }
        return jwt.getJWTClaimsSet();
    }
}
