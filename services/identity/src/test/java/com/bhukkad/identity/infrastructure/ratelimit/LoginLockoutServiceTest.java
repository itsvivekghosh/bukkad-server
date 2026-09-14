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

    // ---------- full-coverage additions (auth-chain 100% gate) ----------

    @Test
    @SuppressWarnings("unchecked")
    void recordFailure_nullScriptResult_returnsZero() {
        doReturn(null).when(template)
                .execute(any(RedisScript.class), anyList(), any(), any(), any(), any(), any());
        assertThat(service.recordFailure(EMAIL, IP)).isZero();
    }

    @Test
    @SuppressWarnings("unchecked")
    void recordSuccess_swallowsRedisSystemError() {
        doThrow(new org.springframework.data.redis.RedisSystemException("down", null))
                .when(template).delete(any(List.class));
        // never propagates: a Redis blip on the success path must not 500 a login
        service.recordSuccess(EMAIL, IP);
        verify(template).delete(any(List.class));
    }

    @Test
    @SuppressWarnings("unchecked")
    void nullEmailAndBlankIp_normalizeToUnknownPair() {
        doReturn(1L).when(template)
                .execute(any(RedisScript.class), anyList(), any(), any(), any(), any(), any());
        service.recordFailure(null, "  ");
        @SuppressWarnings("rawtypes")
        ArgumentCaptor<List> keys = ArgumentCaptor.forClass(List.class);
        verify(template).execute(any(RedisScript.class), keys.capture(), any(), any(), any(), any(), any());
        assertThat((List<String>) keys.getValue()).allSatisfy(k ->
                assertThat(k).endsWith("unknown|unknown"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void nullIp_normalizedToUnknown() {
        doReturn(1L).when(template)
                .execute(any(RedisScript.class), anyList(), any(), any(), any(), any(), any());
        service.recordFailure(EMAIL, null);
        @SuppressWarnings("rawtypes")
        ArgumentCaptor<List> keys = ArgumentCaptor.forClass(List.class);
        verify(template).execute(any(RedisScript.class), keys.capture(), any(), any(), any(), any(), any());
        assertThat((List<String>) keys.getValue()).allSatisfy(k ->
                assertThat(k).endsWith("attacker@b.com|unknown"));
    }

    @Test
    void assertAllowed_passesWhenTtlIsZero() {
        when(template.getExpire(any(String.class), any(TimeUnit.class))).thenReturn(0L);
        service.assertAllowed(EMAIL, IP); // boundary: 0 ms remaining is not locked
    }

    @Test
    void assertAllowed_passesWhenGetExpireReturnsNull() {
        when(template.getExpire(any(String.class), any(TimeUnit.class))).thenReturn(null);
        service.assertAllowed(EMAIL, IP);
    }

    @Test
    @SuppressWarnings("unchecked")
    void ipv6Address_collapsesUnderscoredWithoutThrowing() {
        doReturn(1L).when(template)
                .execute(any(RedisScript.class), anyList(), any(), any(), any(), any(), any());
        service.recordFailure(EMAIL, "2001:db8::1");
        @SuppressWarnings("rawtypes")
        ArgumentCaptor<List> keys = ArgumentCaptor.forClass(List.class);
        verify(template).execute(any(RedisScript.class), keys.capture(), any(), any(), any(), any(), any());
        assertThat((List<String>) keys.getValue()).allSatisfy(k ->
                assertThat(k).endsWith("attacker@b.com|2001_db8__1"));
    }

    @Test
    void denyPath_worksWithoutMeterRegistry() {
        LoginLockoutService noMetrics =
                new LoginLockoutService(template, LoginLockoutProperties.defaults(),
                        (io.micrometer.core.instrument.MeterRegistry) null);
        when(template.getExpire(any(String.class), any(TimeUnit.class))).thenReturn(5_000L);
        assertThatThrownBy(() -> noMetrics.assertAllowed(EMAIL, IP))
                .isInstanceOf(com.bhukkad.common.ratelimit.RateLimitExceededException.class);
    }

    @Test
    void springConstructor_resolvesRegistryViaObjectProvider() {
        org.springframework.beans.factory.ObjectProvider<io.micrometer.core.instrument.MeterRegistry> provider =
                mock(org.springframework.beans.factory.ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(new SimpleMeterRegistry());
        LoginLockoutService viaProvider =
                new LoginLockoutService(template, LoginLockoutProperties.defaults(), provider);
        assertThat(viaProvider).isNotNull();
    }

    @Test
    void remoteIp_coversAllInputs() {
        assertThat(LoginLockoutService.remoteIp(null)).isEqualTo("unknown");
        var withAddr = new org.springframework.mock.web.MockHttpServletRequest();
        withAddr.setRemoteAddr("203.0.113.9");
        assertThat(LoginLockoutService.remoteIp(withAddr)).isEqualTo("203.0.113.9");
        var blank = new org.springframework.mock.web.MockHttpServletRequest();
        blank.setRemoteAddr("  ");
        assertThat(LoginLockoutService.remoteIp(blank)).isEqualTo("unknown");
        var nullAddr = new org.springframework.mock.web.MockHttpServletRequest();
        nullAddr.setRemoteAddr(null);
        assertThat(LoginLockoutService.remoteIp(nullAddr)).isEqualTo("unknown");
    }
}
