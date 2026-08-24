package com.bhukkad.security;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.lang.reflect.Field;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class JwtRefreshRotationServiceTest {

    @Mock
    private StringRedisTemplate stringRedisTemplate;
    @Mock
    private ValueOperations<String, String> valueOperations;

    private JwtRefreshRotationService service;

    @BeforeEach
    void setUp() throws Exception {
        service = new JwtRefreshRotationService(stringRedisTemplate);
        Field f = service.getClass().getDeclaredField("refreshExpirationMs");
        f.setAccessible(true);
        f.set(service, 604800000L);
    }

    @Test
    void revoke_storesJtiWithRefreshTtl() {
        when(stringRedisTemplate.opsForValue()).thenReturn(valueOperations);

        service.revoke("jti-123");

        verify(valueOperations).set("bhukkad:revoked-jti:jti-123", "1", 604800000L, TimeUnit.MILLISECONDS);
    }

    @Test
    void revoke_blankJti_isIgnored() {
        service.revoke("  ");

        verify(stringRedisTemplate, org.mockito.Mockito.never()).opsForValue();
    }

    @Test
    void revoke_nullJti_isIgnored() {
        service.revoke(null);

        verify(stringRedisTemplate, org.mockito.Mockito.never()).opsForValue();
    }

    @Test
    void isRevoked_returnsTrueWhenKeyExists() {
        when(stringRedisTemplate.hasKey("bhukkad:revoked-jti:jti-123")).thenReturn(true);

        assertTrue(service.isRevoked("jti-123"));
    }

    @Test
    void isRevoked_returnsFalseWhenKeyMissing() {
        when(stringRedisTemplate.hasKey("bhukkad:revoked-jti:jti-123")).thenReturn(false);

        assertFalse(service.isRevoked("jti-123"));
    }

    @Test
    void isRevoked_nullJti_returnsFalse() {
        assertFalse(service.isRevoked(null));
    }

    @Test
    void isRevoked_blankJti_returnsFalse() {
        assertFalse(service.isRevoked("  "));
    }

    @Test
    void redisFailure_failsOpen() {
        when(stringRedisTemplate.hasKey("bhukkad:revoked-jti:jti-123"))
                .thenThrow(new RuntimeException("connection refused"));

        assertFalse(service.isRevoked("jti-123"));
    }

    @Test
    void revoke_redisFailure_doesNotPropagate() {
        when(stringRedisTemplate.opsForValue())
                .thenThrow(new RuntimeException("connection refused"));

        service.revoke("jti-123"); // must not throw
    }

    @Test
    void rotate_revokesOldJti() {
        when(stringRedisTemplate.opsForValue()).thenReturn(valueOperations);

        service.rotate("old-jti");

        verify(valueOperations).set("bhukkad:revoked-jti:old-jti", "1", 604800000L, TimeUnit.MILLISECONDS);
    }
}