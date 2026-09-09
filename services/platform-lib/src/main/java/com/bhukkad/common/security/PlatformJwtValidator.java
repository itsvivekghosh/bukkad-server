package com.bhukkad.common.security;

import com.nimbusds.jose.crypto.MACVerifier;
import com.nimbusds.jose.crypto.RSASSAVerifier;
import com.nimbusds.jose.jwk.JWK;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestClient;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

/**
 * Validates platform JWTs (HS256 shared secret or RS256 via JWKS).
 *
 * <p>Production hardening notes:
 * <ul>
 *   <li>The JWKS {@link RestClient} uses explicit connect/read timeouts — an
 *       unbounded fetch on the request path would let a slow IdP exhaust the
 *       servlet thread pool (latency/DoS).</li>
 *   <li>On an unknown {@code kid} (key rotation) the JWKS cache is refreshed
 *       once, rate-limited by {@link #UNKNOWN_KID_REFRESH_MIN_MILLIS}, so new
 *       keys are accepted within seconds instead of at cache TTL expiry.</li>
 *   <li>A failed JWKS refresh keeps serving the last good key set (stale-read
 *       beats fail-closed for availability; signatures still verified).</li>
 * </ul>
 */
@Component
public class PlatformJwtValidator {

    private static final Logger log = LoggerFactory.getLogger(PlatformJwtValidator.class);
    private static final long JWKS_TTL_MILLIS = 60 * 60 * 1000L;
    /** Minimum spacing between refreshes triggered by unknown kids. */
    private static final long UNKNOWN_KID_REFRESH_MIN_MILLIS = 30 * 1000L;
    private static final Duration JWKS_CONNECT_TIMEOUT = Duration.ofSeconds(2);
    private static final Duration JWKS_READ_TIMEOUT = Duration.ofSeconds(3);

    private final PlatformJwtProperties properties;
    private final RestClient restClient;

    private volatile JWKSet cachedJwks;
    private volatile long lastFetchMillis;
    private volatile long lastUnknownKidRefreshMillis;

    @Autowired
    public PlatformJwtValidator(PlatformJwtProperties properties) {
        this(properties, defaultRestClient());
    }

    PlatformJwtValidator(PlatformJwtProperties properties, RestClient restClient) {
        this.properties = properties;
        this.restClient = restClient;
    }

    static RestClient defaultRestClient() {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout((int) JWKS_CONNECT_TIMEOUT.toMillis());
        factory.setReadTimeout((int) JWKS_READ_TIMEOUT.toMillis());
        return RestClient.builder().requestFactory(factory).build();
    }

    public boolean isEnabled() {
        return properties.enabled();
    }

    public Optional<TokenPrincipal> validate(String token) {
        try {
            SignedJWT jwt = SignedJWT.parse(token);
            if (!verifySignature(jwt)) {
                log.debug("JWT rejected: invalid signature");
                return Optional.empty();
            }
            JWTClaimsSet claims = jwt.getJWTClaimsSet();
            if (claims.getExpirationTime() == null
                    || claims.getExpirationTime().toInstant().isBefore(Instant.now())) {
                log.debug("JWT rejected: missing or past expiration");
                return Optional.empty();
            }
            if (claims.getNotBeforeTime() != null
                    && claims.getNotBeforeTime().toInstant().isAfter(Instant.now())) {
                log.debug("JWT rejected: not yet valid");
                return Optional.empty();
            }
            if (StringUtils.hasText(properties.issuer())
                    && !properties.issuer().equals(claims.getIssuer())) {
                log.debug("JWT rejected: unexpected issuer");
                return Optional.empty();
            }
            if (StringUtils.hasText(properties.audience())
                    && !claims.getAudience().contains(properties.audience())) {
                log.debug("JWT rejected: unexpected audience");
                return Optional.empty();
            }
            if (claims.getSubject() == null) {
                log.debug("JWT rejected: missing subject");
                return Optional.empty();
            }
            long userId;
            try {
                userId = Long.parseLong(claims.getSubject());
            } catch (NumberFormatException e) {
                log.debug("JWT rejected: subject is not numeric");
                return Optional.empty();
            }
            return Optional.of(new TokenPrincipal(userId,
                    claims.getStringClaim("email"),
                    claims.getStringClaim("scope")));
        } catch (Exception e) {
            log.debug("JWT validation failed: {}", e.getMessage());
            return Optional.empty();
        }
    }

    private boolean verifySignature(SignedJWT jwt) throws Exception {
        if (StringUtils.hasText(properties.jwksUrl())) {
            return verifyRsa(jwt);
        }
        if (StringUtils.hasText(properties.secret())) {
            return jwt.verify(new MACVerifier(properties.secret()));
        }
        return false;
    }

    private boolean verifyRsa(SignedJWT jwt) throws Exception {
        JWKSet jwks = jwksCache();
        String kid = jwt.getHeader().getKeyID();
        JWK jwk = jwks.getKeyByKeyId(kid);
        if (!(jwk instanceof RSAKey rsaKey)) {
            // Unknown kid usually means the IdP rotated keys; refresh once
            // (rate-limited) before giving up so rotation causes seconds of
            // rejection, not up to the full TTL.
            if (StringUtils.hasText(kid) && refreshOnUnknownKid()) {
                jwk = jwksCache().getKeyByKeyId(kid);
            }
            if (!(jwk instanceof RSAKey rsaKeyAfterRefresh)) {
                log.debug("JWT rejected: no matching RSA key for kid={}", kid);
                return false;
            }
            return jwt.verify(new RSASSAVerifier(rsaKeyAfterRefresh));
        }
        return jwt.verify(new RSASSAVerifier(rsaKey));
    }

    private boolean refreshOnUnknownKid() {
        long now = System.currentTimeMillis();
        if (now - lastUnknownKidRefreshMillis < UNKNOWN_KID_REFRESH_MIN_MILLIS) {
            return false;
        }
        lastUnknownKidRefreshMillis = now;
        return true;
    }

    private JWKSet jwksCache() throws Exception {
        JWKSet current = cachedJwks;
        long now = System.currentTimeMillis();
        if (current != null && now - lastFetchMillis < JWKS_TTL_MILLIS) {
            return current;
        }
        synchronized (this) {
            if (cachedJwks != null && System.currentTimeMillis() - lastFetchMillis < JWKS_TTL_MILLIS) {
                return cachedJwks;
            }
            try {
                String body = restClient.get().uri(properties.jwksUrl()).retrieve().body(String.class);
                JWKSet parsed = JWKSet.parse(body);
                if (parsed.getKeys().isEmpty()) {
                    throw new IllegalStateException("JWKS endpoint returned an empty key set");
                }
                cachedJwks = parsed;
                lastFetchMillis = System.currentTimeMillis();
                return cachedJwks;
            } catch (Exception e) {
                if (cachedJwks != null) {
                    // Serve the last good keys rather than rejecting every
                    // request during an IdP hiccup; retry at the next expiry.
                    log.warn("JWKS refresh failed, serving stale keys: {}", e.getMessage());
                    lastFetchMillis = System.currentTimeMillis();
                    return cachedJwks;
                }
                throw e;
            }
        }
    }
}
