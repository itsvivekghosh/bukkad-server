package com.bhukkad.common.security;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.Duration;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * P1 REVOCATION: epoch store behaviour — key format
 * {@code jwt:revoked-before:<userId>}, ISO-8601 value with a TTL, monotonic
 * (never shortened) epochs, best-effort writes and propagating reads.
 */
@SuppressWarnings("unchecked")
class JwtRevocationServiceTest {

    private final StringRedisTemplate redis = mock(StringRedisTemplate.class);
    private final ValueOperations<String, String> valueOps = mock(ValueOperations.class);

    JwtRevocationServiceTest() {
        when(redis.opsForValue()).thenReturn(valueOps);
    }

    private static ObjectProvider<StringRedisTemplate> providerOf(StringRedisTemplate template) {
        return new ObjectProvider<>() {
            @Override
            public StringRedisTemplate getObject() {
                return template;
            }

            @Override
            public StringRedisTemplate getObject(Object... args) {
                return template;
            }

            @Override
            public StringRedisTemplate getIfAvailable() {
                return template;
            }

            @Override
            public StringRedisTemplate getIfUnique() {
                return template;
            }
        };
    }

    private JwtRevocationService service(Duration ttl) {
        return new JwtRevocationService(providerOf(redis), ttl);
    }

    @Test
    void revoke_storesTruncatedEpochUnderPrefixedKeyWithTtl() {
        when(valueOps.get("jwt:revoked-before:42")).thenReturn(null);

        service(Duration.ofMinutes(15)).revoke(42L,
                Instant.parse("2026-09-11T10:00:00.123456789Z"));

        // Seconds precision — matches JWT iat granularity.
        verify(valueOps).set(eq("jwt:revoked-before:42"),
                eq("2026-09-11T10:00:00Z"), eq(Duration.ofMinutes(15)));
    }

    @Test
    void revoke_neverShortensAnExistingLaterEpoch() {
        when(valueOps.get("jwt:revoked-before:7")).thenReturn("2026-09-11T11:00:00Z");

        service(Duration.ofMinutes(15)).revoke(7L, Instant.parse("2026-09-11T09:00:00Z"));

        verify(valueOps).set("jwt:revoked-before:7", "2026-09-11T11:00:00Z", Duration.ofMinutes(15));
    }

    @Test
    void revoke_advancesToALaterEpoch() {
        when(valueOps.get("jwt:revoked-before:7")).thenReturn("2026-09-11T09:00:00Z");

        service(Duration.ofMinutes(15)).revoke(7L, Instant.parse("2026-09-11T10:30:00Z"));

        verify(valueOps).set("jwt:revoked-before:7", "2026-09-11T10:30:00Z", Duration.ofMinutes(15));
    }

    @Test
    void revoke_swallowsRedisFailures_logoutMustNotBreak() {
        when(valueOps.get(any())).thenThrow(new RuntimeException("connection refused"));
        JwtRevocationService service = service(Duration.ofMinutes(15));

        assertThatCode(() -> service.revoke(3L, Instant.now())).doesNotThrowAnyException();
        verify(valueOps, never()).set(any(), any(), any(Duration.class));
    }

    @Test
    void revokedBefore_parsesTheStoredInstant() {
        when(valueOps.get("jwt:revoked-before:5")).thenReturn("2026-09-11T09:15:30Z");

        assertThat(service(Duration.ofMinutes(15)).revokedBefore(5L))
                .isEqualTo(Instant.parse("2026-09-11T09:15:30Z"));
    }

    @Test
    void revokedBefore_absentKeyIsNotRevoked() {
        when(valueOps.get("jwt:revoked-before:5")).thenReturn(null);

        assertThat(service(Duration.ofMinutes(15)).revokedBefore(5L)).isNull();
    }

    @Test
    void revokedBefore_propagatesRedisErrors_forTheValidatorFailOpenPolicy() {
        when(valueOps.get(any())).thenThrow(new RuntimeException("MOVED 12345"));

        assertThatThrownBy(() -> service(Duration.ofMinutes(15)).revokedBefore(5L))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("MOVED");
    }

    @Test
    void noRedisTemplate_contextsStayInertAndBootClean() {
        JwtRevocationService inert = new JwtRevocationService(providerOf(null));

        assertThat(inert.isAvailable()).isFalse();
        assertThatCode(() -> inert.revoke(1L, Instant.now())).doesNotThrowAnyException();
        assertThat(inert.revokedBefore(1L)).isNull();
    }

    @Test
    void nonPositiveTtl_fallsBackToTheDefaultKeyTtl() {
        when(valueOps.get("jwt:revoked-before:8")).thenReturn(null);

        service(Duration.ZERO).revoke(8L, Instant.parse("2026-09-11T08:00:00Z"));

        verify(valueOps).set("jwt:revoked-before:8", "2026-09-11T08:00:00Z", Duration.ofMinutes(15));
    }
}
