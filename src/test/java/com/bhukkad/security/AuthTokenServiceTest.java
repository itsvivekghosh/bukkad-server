package com.bhukkad.security;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.SetOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.Duration;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AuthTokenServiceTest {

    @Mock
    private StringRedisTemplate stringRedisTemplate;
    @Mock
    private ValueOperations<String, String> valueOps;
    @Mock
    private SetOperations<String, String> setOps;

    @InjectMocks
    private AuthTokenService service;

    private static final Long USER_ID = 1L;
    private static final String TOKEN = "refresh-token-abc";

    @BeforeEach
    void setUp() {
        org.mockito.Mockito.lenient().when(stringRedisTemplate.opsForValue()).thenReturn(valueOps);
        org.mockito.Mockito.lenient().when(stringRedisTemplate.opsForSet()).thenReturn(setOps);
    }

    @Test
    void storeRefreshToken_setsKeyAndIndex() {
        service.storeRefreshToken(USER_ID, TOKEN, 60000L);

        verify(valueOps).set("auth:refresh:1:" + TOKEN, "1", 60000L, TimeUnit.MILLISECONDS);
        verify(setOps).add("auth:refresh:index:1", "auth:refresh:1:" + TOKEN);
        verify(stringRedisTemplate).expire("auth:refresh:index:1", 60000L, TimeUnit.MILLISECONDS);
    }

    @Test
    void isRefreshTokenValid_returnsTrue_whenExists() {
        when(stringRedisTemplate.hasKey("auth:refresh:1:" + TOKEN)).thenReturn(true);
        assertTrue(service.isRefreshTokenValid(USER_ID, TOKEN));
    }

    @Test
    void isRefreshTokenValid_returnsFalse_whenMissing() {
        when(stringRedisTemplate.hasKey("auth:refresh:1:" + TOKEN)).thenReturn(false);
        assertFalse(service.isRefreshTokenValid(USER_ID, TOKEN));
    }

    @Test
    void revokeRefreshToken_deletesKeyAndIndexEntry() {
        service.revokeRefreshToken(USER_ID, TOKEN);
        verify(stringRedisTemplate).delete("auth:refresh:1:" + TOKEN);
        verify(setOps).remove("auth:refresh:index:1", "auth:refresh:1:" + TOKEN);
    }

    @Test
    void revokeAllRefreshTokens_deletesAllIndexedKeys() {
        when(setOps.members("auth:refresh:index:1")).thenReturn(
                Set.of("auth:refresh:1:a", "auth:refresh:1:b"));

        service.revokeAllRefreshTokens(USER_ID);

        verify(stringRedisTemplate).delete(Set.of("auth:refresh:1:a", "auth:refresh:1:b"));
        verify(stringRedisTemplate).delete("auth:refresh:index:1");
    }

    @Test
    void revokeAllRefreshTokens_handlesEmptyIndex() {
        when(setOps.members("auth:refresh:index:1")).thenReturn(Set.of());

        service.revokeAllRefreshTokens(USER_ID);
        verify(stringRedisTemplate).delete("auth:refresh:index:1");
    }

    @Test
    void blacklistAccessToken_skipsWhenNonPositiveTtl() {
        service.blacklistAccessToken("token", 0L);
        service.blacklistAccessToken("token", -5L);
        verify(valueOps, never()).set(anyString(), anyString(), any(Long.class), any(TimeUnit.class));
    }

    @Test
    void blacklistAccessToken_storesWithTtl() {
        service.blacklistAccessToken("access-token", 300000L);
        verify(valueOps).set("auth:blacklist:access-token", "1", 300000L, TimeUnit.MILLISECONDS);
    }

    @Test
    void isAccessTokenBlacklisted_delegatesToRedis() {
        when(stringRedisTemplate.hasKey("auth:blacklist:token")).thenReturn(true);
        assertTrue(service.isAccessTokenBlacklisted("token"));
    }

    @Test
    void createPasswordResetToken_returnsToken() {
        String token = service.createPasswordResetToken("user@example.com", Duration.ofMinutes(10));
        assertNotNull(token);
        verify(valueOps).set(anyString(), anyString(), any(Duration.class));
    }

    @Test
    void validatePasswordResetToken_returnsEmail() {
        when(valueOps.get("auth:reset:some-token")).thenReturn("user@example.com");
        assertEquals("user@example.com", service.validatePasswordResetToken("some-token"));
    }

    @Test
    void consumePasswordResetToken_deletesKey() {
        service.consumePasswordResetToken("some-token");
        verify(stringRedisTemplate).delete("auth:reset:some-token");
    }
}