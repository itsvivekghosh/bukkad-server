package com.bhukkad.security;

import io.jsonwebtoken.*;
import io.jsonwebtoken.security.SignatureException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

@Component
public class JwtTokenProvider {

    private static final Logger logger = LoggerFactory.getLogger(JwtTokenProvider.class);

    private final JwtSecretRotationService secretRotationService;

    @Value("${app.jwt.expiration}")
    private long jwtExpirationMs;

    @Value("${app.jwt.refresh-expiration:604800000}")
    private long refreshExpirationMs;

    @Value("${app.jwt.mfa-expiration:300000}")
    private long mfaExpirationMs;

    public static final String TOKEN_TYPE_CLAIM = "type";
    public static final String ACCESS_TOKEN_TYPE = "access";
    public static final String REFRESH_TOKEN_TYPE = "refresh";
    public static final String MFA_TOKEN_TYPE = "mfa";
    public static final String USER_ID_CLAIM = "uid";

    public JwtTokenProvider(JwtSecretRotationService secretRotationService) {
        this.secretRotationService = secretRotationService;
    }

    /**
     * Extract username from JWT token
     */
    public String extractUsername(String token) {
        return extractClaim(token, Claims::getSubject);
    }

    /**
     * Extract specific claim from JWT token
     */
    public <T> T extractClaim(String token, Function<Claims, T> claimsResolver) {
        final Claims claims = extractAllClaims(token);
        return claimsResolver.apply(claims);
    }

    /**
     * Generate JWT token for user
     */
    public String generateToken(UserDetails userDetails) {
        return generateAccessToken(userDetails);
    }

    public String generateAccessToken(UserDetails userDetails) {
        Map<String, Object> claims = new HashMap<>();
        claims.put(TOKEN_TYPE_CLAIM, ACCESS_TOKEN_TYPE);
        return generateToken(claims, userDetails, jwtExpirationMs);
    }

    public String generateRefreshToken(UserDetails userDetails) {
        Map<String, Object> claims = new HashMap<>();
        claims.put(TOKEN_TYPE_CLAIM, REFRESH_TOKEN_TYPE);
        return generateToken(claims, userDetails, refreshExpirationMs);
    }

    /**
     * Generates a short-lived token authorising the MFA-verify step after a
     * password login on a TOTP-enabled privileged account. Carries the user id
     * and an {@code mfa} claim so the verify endpoint can bind the challenge to
     * the account that logged in.
     */
    public String generateMfaToken(Long userId, String email) {
        Map<String, Object> claims = new HashMap<>();
        claims.put(TOKEN_TYPE_CLAIM, MFA_TOKEN_TYPE);
        claims.put(USER_ID_CLAIM, userId);
        return Jwts.builder()
                .setClaims(claims)
                .setSubject(email)
                .setIssuedAt(new Date())
                .setExpiration(new Date(System.currentTimeMillis() + mfaExpirationMs))
                .signWith(secretRotationService.currentSigningKey(), SignatureAlgorithm.HS256)
                .compact();
    }

    /** Extracts the numeric user id claim (used by the MFA verify step). */
    public Long extractUserId(String token) {
        return extractClaim(token, claims -> {
            Object value = claims.get(USER_ID_CLAIM);
            if (value instanceof Number number) {
                return number.longValue();
            }
            return null;
        });
    }

    /** Validates that the token is an unexpired MFA challenge token. */
    public boolean validateMfaToken(String token) {
        if (token == null || token.isBlank()) {
            return false;
        }
        try {
            if (isTokenExpired(token)) {
                return false;
            }
            return MFA_TOKEN_TYPE.equals(
                    extractClaim(token, claims -> claims.get(TOKEN_TYPE_CLAIM, String.class)));
        } catch (Exception ex) {
            logger.warn("MFA token validation failed: {}", ex.getMessage());
            return false;
        }
    }

    public boolean isRefreshToken(String token) {
        try {
            return REFRESH_TOKEN_TYPE.equals(extractClaim(token, claims -> claims.get(TOKEN_TYPE_CLAIM, String.class)));
        } catch (Exception e) {
            return false;
        }
    }

    public long getRemainingValidityMs(String token) {
        Date expiration = extractExpiration(token);
        return Math.max(0, expiration.getTime() - System.currentTimeMillis());
    }

    /**
     * Generate JWT token with extra claims
     */
    public String generateToken(Map<String, Object> extraClaims, UserDetails userDetails) {
        return generateToken(extraClaims, userDetails, jwtExpirationMs);
    }

    private String generateToken(Map<String, Object> extraClaims, UserDetails userDetails, long expirationMs) {
        Date now = new Date();
        Date expiryDate = new Date(now.getTime() + expirationMs);

        return Jwts
                .builder()
                .setClaims(extraClaims)
                .setSubject(userDetails.getUsername())
                .setIssuedAt(now)
                .setExpiration(expiryDate)
                .signWith(secretRotationService.currentSigningKey(), SignatureAlgorithm.HS256)
                .compact();
    }

    /**
     * Validate JWT token
     */
    public boolean isTokenValid(String token, UserDetails userDetails) {
        try {
            final String username = extractUsername(token);
            return (username.equals(userDetails.getUsername())) && !isTokenExpired(token);
        } catch (Exception e) {
            logger.error("Token validation error: {}", e.getMessage());
            return false;
        }
    }

    /**
     * Validate JWT token structure and signature against the current and
     * previous signing keys (rotation grace period).
     */
    public boolean validateToken(String authToken) {
        try {
            parse(authToken);
            return true;
        } catch (SignatureException e) {
            logger.error("Invalid JWT signature: {}", e.getMessage());
        } catch (MalformedJwtException e) {
            logger.error("Invalid JWT token: {}", e.getMessage());
        } catch (ExpiredJwtException e) {
            logger.error("JWT token is expired: {}", e.getMessage());
        } catch (UnsupportedJwtException e) {
            logger.error("JWT token is unsupported: {}", e.getMessage());
        } catch (IllegalArgumentException e) {
            logger.error("JWT claims string is empty: {}", e.getMessage());
        }
        return false;
    }

    /**
     * Check if token is expired
     */
    private boolean isTokenExpired(String token) {
        return extractExpiration(token).before(new Date());
    }

    /**
     * Extract expiration date from token
     */
    private Date extractExpiration(String token) {
        return extractClaim(token, Claims::getExpiration);
    }

    /**
     * Extract all claims from token, trying the current then previous keys.
     */
    private Claims extractAllClaims(String token) {
        return parse(token).getBody();
    }

    /**
     * Parse a signed JWT, attempting each active key (current first, then the
     * previous secret still inside its grace period).
     */
    private Jws<Claims> parse(String token) {
        List<SecretKey> keys = secretRotationService.validationKeys();
        JwtException lastException = null;
        for (SecretKey key : keys) {
            try {
                return Jwts.parserBuilder()
                        .setSigningKey(key)
                        .build()
                        .parseClaimsJws(token);
            } catch (JwtException ex) {
                lastException = ex;
            }
        }
        if (lastException != null) {
            throw lastException;
        }
        throw new MalformedJwtException("No signing key matched token");
    }
}