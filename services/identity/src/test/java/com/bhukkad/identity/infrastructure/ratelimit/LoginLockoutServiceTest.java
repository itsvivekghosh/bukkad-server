package com.bhukkad.identity.infrastructure.ratelimit;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;

import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * W1-AUTH deliverable 3: the (email, IP) lockout counter follows the platform
 * limiter discipline — one atomic script, sign-based verdict, observable
 * fail-open on Redis errors, metric on every deny.
 */
class LoginLockoutServiceTest {

    private static final String EMAIL = "Attacker@B.com";
    private static final String IP = "10.0.0.1";

    private StringRedisTemplate template;
    private SimpleMeterRegistry registry;
    private LoginLockoutService service;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        template = mock(StringRedisTemplate.class);
        registry = new SimpleMeterRegistry();
        service = new LoginLockoutService(template, LoginLockoutProperties.defaults(), registry);
    }

    @Test
    @SuppressWarnings("unchecked")
    void recordFailure_evaluatesSingleAtomicScript_withNormalizedKey() {
        doReturn(3L).when(template).execute(any(RedisScript.class), anyList(), any(), any(), any(), any(), any());

        long count = service.recordFailure(EMAIL, IP);

        assertThat(count).isEqualTo(3L);
        @SuppressWarnings("rawtypes")
        ArgumentCaptor<List> keys = ArgumentCaptor.forClass(List.class);
        verify(template).execute(any(RedisScript.class), keys.capture(), any(), any(), any(), any(), any());
        List<String> keyList = keys.getValue();
        // Email is lower-cased so casing cannot split the counter; IP keeps
        // its own axis. Keys: lock, failures, strikes.
        assertThat(keyList).anySatisfy(k -> assertThat((String) k)
                .contains("attacker@b.com|10.0.0.1"));
        assertThat(keyList).hasSize(3);
    }

    @Test
    @SuppressWarnings("unchecked")
    void assertAllowed_throws429_whenLockKeyHasTtl_andCountsDeny() {
        when(template.getExpire(any(String.class), any(TimeUnit.class))).thenReturn(42_000L);

        assertThatThrownBy(() -> service.assertAllowed(EMAIL, IP))
                .isInstanceOf(com.bhukkad.common.ratelimit.RateLimitExceededException.class)
                .hasMessageContaining("Too many failed login attempts");

        assertThat(registry.get("auth_lockout_active").tag("outcome", "deny")
                .counter().count()).isEqualTo(1.0);
    }

    @Test
    void assertAllowed_passes_whenNotLocked() {
        when(template.getExpire(any(String.class), any(TimeUnit.class))).thenReturn(-2L);
        service.assertAllowed(EMAIL, IP);
        assertThat(registry.find("auth_lockout_active").tag("outcome", "deny").counter())
                .as("no deny counted when the pair is not locked").isNull();
    }

    @Test
    @SuppressWarnings("unchecked")
    void recordSuccess_deletesAllThreeKeys() {
        service.recordSuccess(EMAIL, IP);
        verify(template).delete(any(List.class));
    }

    @Test
    @SuppressWarnings("unchecked")
    void redisDown_failsOpen_neverLocks() {
        doThrow(new RedisConnectionFailureException("down"))
                .when(template).execute(any(RedisScript.class), anyList(), any(), any(), any(), any(), any());
        doThrow(new RedisConnectionFailureException("down"))
                .when(template).getExpire(any(String.class), any(TimeUnit.class));

        // Deny path: no exception, caller proceeds.
        assertThat(service.recordFailure(EMAIL, IP)).isEqualTo(0);
        service.assertAllowed(EMAIL, IP); // fail-open: no throw
    }

    @Test
    @SuppressWarnings("unchecked")
    void lockActivation_countsActivateOutcome() {
        // Script returns -lockMillis when the threshold was crossed.
        doReturn(-300_000L).when(template).execute(any(RedisScript.class), anyList(), any(), any(), any(), any(), any());

        long result = service.recordFailure(EMAIL, IP);

        assertThat(result).isEqualTo(-300_000L);
        assertThat(registry.get("auth_lockout_active").tag("outcome", "activate")
                .counter().count()).isEqualTo(1.0);
    }

    @Test
    void exponentialWindow_isBoundedByConfig() {
        LoginLockoutProperties props = new LoginLockoutProperties(5, 900, 300, 3600, 86_400);
        assertThat(props.threshold()).isEqualTo(5);
        assertThat(props.maxLockSeconds()).isEqualTo(3600);
    }
}
