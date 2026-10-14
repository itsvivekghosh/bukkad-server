package com.bhukkad.common.security;

import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;

/**
 * Proves service-to-service calls carry a valid service JWT on the
 * {@code X-Service-Token} header.
 *
 * <p>The token is a minimal HS256 JWT with the service name as the subject.
 * Validation is handled by {@link ServiceJwtAuthFilter} on the receiving
 * service, which reads the same shared secret from
 * {@link ServiceAuthProperties}.</p>
 *
 * <p>The bean is always created; if {@code app.auth.service.jwt-secret} is
 * not configured it logs a warning and {@link #serviceToken()} returns
 * {@code null} so callers can skip the header gracefully.</p>
 */
@Component
public class ServiceJwtAuthTokenProvider {

    private static final Logger log = LoggerFactory.getLogger(ServiceJwtAuthTokenProvider.class);

    private final ServiceAuthProperties properties;
    private final String serviceName;
    private SecretKey key;

    public ServiceJwtAuthTokenProvider(ServiceAuthProperties properties,
                                       org.springframework.core.env.Environment env) {
        this.properties = properties;
        this.serviceName = env.getProperty("spring.application.name", "unknown");
    }

    @PostConstruct
    void init() {
        if (properties.getJwtSecret() == null || properties.getJwtSecret().isBlank()) {
            log.warn("Service JWT secret is not configured; service-to-service auth tokens will not be issued");
            return;
        }
        this.key = Keys.hmacShaKeyFor(properties.getJwtSecret().getBytes(StandardCharsets.UTF_8));
    }

    /**
     * @return a signed service JWT, or {@code null} when service auth is
     *         not configured (so callers can skip the header gracefully).
     */
    public String serviceToken() {
        if (key == null) {
            return null;
        }
        Instant now = Instant.now();
        return Jwts.builder()
                .setSubject(serviceName)
                .setIssuedAt(Date.from(now))
                .setExpiration(Date.from(now.plusSeconds(properties.getTokenTtlSeconds())))
                .claim("service_id", serviceName)
                .signWith(key)
                .compact();
    }
}
