package com.bhukkad.security;

import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

@Service
@RequiredArgsConstructor
public class AuthTokenService {

    private static final String REFRESH_PREFIX = "auth:refresh:";
    private static final String RESET_PREFIX = "auth:reset:";
    private static final String BLACKLIST_PREFIX = "auth:blacklist:";

    private final StringRedisTemplate stringRedisTemplate;

    public void storeRefreshToken(Long userId, String refreshToken, long ttlMs) {
        stringRedisTemplate.opsForValue().set(
                refreshKey(userId, refreshToken),
                "1",
                ttlMs,
                TimeUnit.MILLISECONDS);
    }

    public boolean isRefreshTokenValid(Long userId, String refreshToken) {
        return Boolean.TRUE.equals(stringRedisTemplate.hasKey(refreshKey(userId, refreshToken)));
    }

    public void revokeRefreshToken(Long userId, String refreshToken) {
        stringRedisTemplate.delete(refreshKey(userId, refreshToken));
    }

    public void revokeAllRefreshTokens(Long userId) {
        var keys = stringRedisTemplate.keys(REFRESH_PREFIX + userId + ":*");
        if (keys != null && !keys.isEmpty()) {
            stringRedisTemplate.delete(keys);
        }
    }

    public void blacklistAccessToken(String accessToken, long ttlMs) {
        if (ttlMs <= 0) {
            return;
        }
        stringRedisTemplate.opsForValue().set(
                BLACKLIST_PREFIX + accessToken,
                "1",
                ttlMs,
                TimeUnit.MILLISECONDS);
    }

    public boolean isAccessTokenBlacklisted(String accessToken) {
        return Boolean.TRUE.equals(stringRedisTemplate.hasKey(BLACKLIST_PREFIX + accessToken));
    }

    public String createPasswordResetToken(String email, Duration ttl) {
        String token = UUID.randomUUID().toString();
        stringRedisTemplate.opsForValue().set(RESET_PREFIX + token, email, ttl);
        return token;
    }

    public String validatePasswordResetToken(String token) {
        return stringRedisTemplate.opsForValue().get(RESET_PREFIX + token);
    }

    public void consumePasswordResetToken(String token) {
        stringRedisTemplate.delete(RESET_PREFIX + token);
    }

    private String refreshKey(Long userId, String refreshToken) {
        return REFRESH_PREFIX + userId + ":" + refreshToken;
    }
}
