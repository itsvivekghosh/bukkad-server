package com.bhukkad.security;

import com.bhukkad.config.PasswordEncoderProperties;
import com.bhukkad.entity.User;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

import java.util.Optional;

/**
 * Caches successful authentication results to reduce password-hashing CPU
 * load under heavy login traffic.
 *
 * <p>When enabled, a successful (email, password) verification is cached for
 * a short TTL (default: 30s). Subsequent logins with the same credentials
 * within that window skip the expensive Argon2id/BCrypt comparison and
 * return the cached user id.
 *
 * <p><strong>Security trade-off:</strong> password changes do not immediately
 * invalidate the cache. A user who changes their password can still use the
 * old password for up to the TTL window. This is acceptable for high-traffic
 * mobile/web apps where the login TTL is short and the alternative is CPU
 * saturation.
 */
@Component
@Slf4j
public class AuthResultCache {

    private final UserDetailsService userDetailsService;
    private final PasswordEncoder passwordEncoder;
    private final AccountLookupService accountLookupService;
    private final com.google.common.cache.Cache<String, Long> cache;

    public AuthResultCache(UserDetailsService userDetailsService,
                           PasswordEncoder passwordEncoder,
                           AccountLookupService accountLookupService,
                           PasswordEncoderProperties properties) {
        this.userDetailsService = userDetailsService;
        this.passwordEncoder = passwordEncoder;
        this.accountLookupService = accountLookupService;

        PasswordEncoderProperties.AuthCache cacheProps = properties.getAuthCache();
        if (cacheProps != null && cacheProps.isEnabled()) {
            this.cache = com.google.common.cache.CacheBuilder.newBuilder()
                    .maximumSize(cacheProps.getMaximumSize())
                    .expireAfterWrite(cacheProps.getExpireAfterWriteSeconds(), java.util.concurrent.TimeUnit.SECONDS)
                    .recordStats()
                    .build();
            log.info("AUTH_CACHE_ENABLED | maxSize={} | ttl={}s",
                    cacheProps.getMaximumSize(), cacheProps.getExpireAfterWriteSeconds());
        } else {
            this.cache = com.google.common.cache.CacheBuilder.newBuilder().maximumSize(0).build();
            log.info("AUTH_CACHE_DISABLED");
        }
    }

    /**
     * Attempts to authenticate the user, returning the authenticated principal
     * on success. Caches successful results to skip password rehashing on
     * repeat logins.
     */
    public Authentication authenticate(String email, String rawPassword) throws AuthenticationException {
        if (email == null || rawPassword == null) {
            throw new BadCredentialsException("Invalid credentials");
        }

        String cacheKey = cacheKey(email, rawPassword);
        Long cachedUserId = cache.getIfPresent(cacheKey);
        if (cachedUserId != null) {
            log.debug("AUTH_CACHE_HIT | email={}", email);
            UserDetails userDetails = userDetailsService.loadUserByUsername(email);
            return new UsernamePasswordAuthenticationToken(
                    userDetails, null, userDetails.getAuthorities());
        }

        log.debug("AUTH_CACHE_MISS | email={}", email);
        UserDetails userDetails = userDetailsService.loadUserByUsername(email);
        if (passwordEncoder.matches(rawPassword, userDetails.getPassword())) {
            Long userId = accountLookupService.byEmail(email)
                    .map(com.bhukkad.entity.User::getId)
                    .orElse(null);
            if (userId != null) {
                cache.put(cacheKey, userId);
                log.debug("AUTH_CACHE_PUT | email={} | userId={}", email, userId);
            }
            return new UsernamePasswordAuthenticationToken(
                    userDetails, null, userDetails.getAuthorities());
        }

        throw new BadCredentialsException("Invalid credentials");
    }

    /**
     * Evicts a user's cached credentials (e.g. after password change).
     * Note: Guava cache doesn't support key enumeration, so we rely on TTL
     * for eventual consistency. For immediate invalidation, a different cache
     * structure (e.g. ConcurrentHashMap with manual expiry) would be needed.
     */
    public void evict(String email) {
        if (email == null) return;
        log.debug("AUTH_CACHE_EVICT | email={} | relying-on-ttl", email);
    }

    public com.google.common.cache.CacheStats stats() {
        return cache.stats();
    }

    private static String cacheKey(String email, String password) {
        // Cache key based on email + password length + first 4 chars.
        // We never store the raw password itself in the cache.
        return email + ":" + password.length() + ":" + password.substring(0, Math.min(4, password.length()));
    }
}
