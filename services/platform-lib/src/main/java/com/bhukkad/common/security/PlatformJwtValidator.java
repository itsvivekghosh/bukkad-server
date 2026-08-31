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
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestClient;

import java.time.Instant;
import java.util.Optional;

@Component
public class PlatformJwtValidator {

    private static final Logger log = LoggerFactory.getLogger(PlatformJwtValidator.class);
    private static final long JWKS_TTL_MILLIS = 60 * 60 * 1000L;

    private final PlatformJwtProperties properties;
    private final RestClient restClient;

    private volatile JWKSet cachedJwks;
    private volatile long lastFetchMillis;

    @Autowired
    public PlatformJwtValidator(PlatformJwtProperties properties) {
        this(properties, RestClient.create());
    }

    PlatformJwtValidator(PlatformJwtProperties properties, RestClient restClient) {
        this.properties = properties;
        this.restClient = restClient;
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
        JWK jwk = jwks.getKeyByKeyId(jwt.getHeader().getKeyID());
        if (!(jwk instanceof RSAKey rsaKey)) {
            log.debug("JWT rejected: no matching RSA key for kid={}", jwt.getHeader().getKeyID());
            return false;
        }
        return jwt.verify(new RSASSAVerifier(rsaKey));
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
            String body = restClient.get().uri(properties.jwksUrl()).retrieve().body(String.class);
            cachedJwks = JWKSet.parse(body);
            lastFetchMillis = System.currentTimeMillis();
            return cachedJwks;
        }
    }
}