package com.bhukkad.common.security;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class JwtRevocationServiceTest {

    private static final Duration TTL = Duration.ofMinutes(15);
    private static final long USER_ID = 42L;

    private final StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);
    @SuppressWarnings("unchecked")
    private final ValueOperations<String, String> valueOps = mock(ValueOperations.class);
    private final SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();

    private JwtRevocationService service() {
        when(redisTemplate.opsForValue()).thenReturn(valueOps);
        return new JwtRevocationService(redisTemplate, meterRegistry, TTL);
    }

    @Test
    void isConfigured_reflectsRedisWiring() {
        assertThat(new JwtRevocationService(null, meterRegistry, TTL).isConfigured()).isFalse();
        assertThat(service().isConfigured()).isTrue();
    }

    @Test
    void storedEpoch_isParsedAndReturned() {
        JwtRevocationService service = service();
        Instant epoch = Instant.parse("2026-09-11T12:00:00Z");
        when(valueOps.get(JwtRevocationService.EPOCH_KEY_PREFIX + USER_ID)).thenReturn(epoch.toString());

        assertThat(service.revocationEpoch(USER_ID)).isEqualTo(Optional.of(epoch));
    }

    @Test
    void noEpochStored_returnsEmpty() {
        JwtRevocationService service = service();
        when(valueOps.get(anyString())).thenReturn(null);

        assertThat(service.revocationEpoch(USER_ID)).isEmpty();
        assertThat(meterRegistry.counter(JwtRevocationService.METRIC_BYPASS_REDIS_ERROR).count())
                .isZero();
    }

    @Test
    void redisUnreachable_failsOpen_andCountsBypass() {
        JwtRevocationService service = service();
        when(valueOps.get(anyString()))
                .thenThrow(new RedisConnectionFailureException("connection refused"));

        assertThat(service.revocationEpoch(USER_ID)).isEmpty();
        assertThat(meterRegistry.counter(JwtRevocationService.METRIC_BYPASS_REDIS_ERROR).count())
                .as("the fail-open bypass must be observable")
                .isEqualTo(1.0);
    }

    @Test
    void redisNotConfigured_checkSkippedEntirely() {
        JwtRevocationService service = new JwtRevocationService(null, meterRegistry, TTL);

        assertThat(service.revocationEpoch(USER_ID)).isEmpty();
        assertThatCode(() -> service.revokeTokensIssuedBefore(USER_ID)).doesNotThrowAnyException();
        verify(redisTemplate, never()).opsForValue();
    }

    @Test
    void revokeWritesNowWithEpochTtl() {
        JwtRevocationService service = service();
        Instant before = Instant.now();

        service.revokeTokensIssuedBefore(USER_ID);

        org.mockito.ArgumentCaptor<String> stored = org.mockito.ArgumentCaptor.forClass(String.class);
        verify(valueOps).set(eq(JwtRevocationService.EPOCH_KEY_PREFIX + USER_ID), stored.capture(), eq(TTL));
        Instant written = Instant.parse(stored.getValue());
        assertThat(written).isBetween(before.minusSeconds(5), Instant.now().plusSeconds(5));
    }

    @Test
    void revokeWriteFailure_neverThrows() {
        JwtRevocationService service = service();
        org.mockito.stubbing.Answer<Void> boom = invocation -> {
            throw new RedisConnectionFailureException("down");
        };
        org.mockito.Mockito.doAnswer(boom).when(valueOps)
                .set(anyString(), anyString(), any(Duration.class));

        assertThatCode(() -> service.revokeTokensIssuedBefore(USER_ID)).doesNotThrowAnyException();
    }
}
