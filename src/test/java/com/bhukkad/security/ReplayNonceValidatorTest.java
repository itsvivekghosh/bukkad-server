package com.bhukkad.security;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ReplayNonceValidatorTest {

    @Mock
    private StringRedisTemplate redisTemplate;

    @Mock
    private ValueOperations<String, String> valueOps;

    private ReplayNonceValidator validator;

    @BeforeEach
    void setUp() {
        validator = new ReplayNonceValidator(redisTemplate);
    }

    @Test
    void validateNonce_firstUse_passes() {
        when(redisTemplate.opsForValue()).thenReturn(valueOps);
        when(valueOps.setIfAbsent(anyString(), eq("1"), any())).thenReturn(true);

        // Should not throw
        assertDoesNotThrow(() -> validator.validateNonce("test-nonce-1"));
    }

    @Test
    void validateNonce_replayRejected_throwsSecurityException() {
        when(redisTemplate.opsForValue()).thenReturn(valueOps);
        // Simulate nonce already exists (returns false / null for SET NX)
        when(valueOps.setIfAbsent(anyString(), eq("1"), any())).thenReturn(false);

        SecurityException ex = assertThrows(SecurityException.class,
                () -> validator.validateNonce("replayed-nonce"));
        assertTrue(ex.getMessage().contains("replay"));
    }

    @Test
    void validateNonce_nullNonce_passesToRedis() {
        when(redisTemplate.opsForValue()).thenReturn(valueOps);
        when(valueOps.setIfAbsent(anyString(), eq("1"), any())).thenReturn(true);

        assertDoesNotThrow(() -> validator.validateNonce("any-nonce"));
    }

    @Test
    void validateNonce_usesCorrectKeyPrefix() {
        when(redisTemplate.opsForValue()).thenReturn(valueOps);
        when(valueOps.setIfAbsent(eq("auth:nonce:abc-123"), eq("1"), any())).thenReturn(true);

        validator.validateNonce("abc-123");
        verify(valueOps).setIfAbsent(eq("auth:nonce:abc-123"), eq("1"), any());
    }
}
