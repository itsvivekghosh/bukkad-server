package com.bhukkad.security;

import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

@Service
@RequiredArgsConstructor
public class AuthTokenService {

    private static final String REFRESH_PREFIX = "auth:refresh:";
    private static final String REFRESH_INDEX_PREFIX = "auth:refresh:index:";
    private static final String RESET_PREFIX = "auth:reset:";
    private static final String BLACKLIST_PREFIX = "auth:blacklist:";

    private final StringRedisTemplate stringRedisTemplate;

    public void storeRefreshToken(Long userId, String refreshToken, long ttlMs) {
        String key = refreshKey(userId, refreshToken);
        stringRedisTemplate.opsForValue().set(key, "1", ttlMs, TimeUnit.MILLISECONDS);
        String indexKey = refreshIndexKey(userId);
        stringRedisTemplate.opsForSet().add(indexKey, key);
        stringRedisTemplate.expire(indexKey, ttlMs, TimeUnit.MILLISECONDS);
    }

    public boolean isRefreshTokenValid(Long userId, String refreshToken) {
        return Boolean.TRUE.equals(stringRedisTemplate.hasKey(refreshKey(userId, refreshToken)));
    }

    public void revokeRefreshToken(Long userId, String refreshToken) {
        String key = refreshKey(userId, refreshToken);
        stringRedisTemplate.delete(key);
        stringRedisTemplate.opsForSet().remove(refreshIndexKey(userId), key);
    }

    public void revokeAllRefreshTokens(Long userId) {
        String indexKey = refreshIndexKey(userId);
        Set<String> keys = stringRedisTemplate.opsForSet().members(indexKey);
        if (keys != null && !keys.isEmpty()) {
            stringRedisTemplate.delete(keys);
        }
        stringRedisTemplate.delete(indexKey);
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

    private String refreshIndexKey(Long userId) {
        return REFRESH_INDEX_PREFIX + userId;
    }
}
