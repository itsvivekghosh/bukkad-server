package com.bhukkad.admin.domain.service;

import com.bhukkad.admin.config.FeatureFlagProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.data.redis.connection.Message;
import org.springframework.data.redis.core.HashOperations;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class FeatureFlagServiceTest {

    @Mock private StringRedisTemplate redisTemplate;
    @Mock private HashOperations<String, Object, Object> hashOperations;

    private FeatureFlagProperties properties;
    private FeatureFlagService service;

    @BeforeEach
    void setUp() {
        properties = new FeatureFlagProperties();
        Map<String, Boolean> flags = new HashMap<>();
        flags.put("checkout-v2", true);
        flags.put("gradual", true);
        flags.put("always", true);
        flags.put("never", true);
        properties.setFlags(flags);
        Map<String, Integer> rollout = new HashMap<>();
        rollout.put("gradual", 50);
        rollout.put("always", 100);
        rollout.put("never", 0);
        properties.setRollout(rollout);
        service = new FeatureFlagService(properties, redisTemplate);
        when(redisTemplate.opsForHash()).thenReturn(hashOperations);
    }

    @Test
    void isEnabled_globalSwitchFromConfig() {
        assertThat(service.isEnabled("checkout-v2")).isTrue();
        assertThat(service.isEnabled("unknown")).isFalse();
    }

    @Test
    void isEnabled_readsOverrideFromRedisOnCacheMissAndCachesIt() {
        when(hashOperations.get(FeatureFlagService.OVERRIDES_HASH, "checkout-v2")).thenReturn("false");

        assertThat(service.isEnabled("checkout-v2")).isFalse();
        // second read served from the local cache, no second Redis hit.
        assertThat(service.isEnabled("checkout-v2")).isFalse();
        verify(hashOperations, org.mockito.Mockito.times(1))
                .get(FeatureFlagService.OVERRIDES_HASH, "checkout-v2");
    }

    @Test
    void isEnabled_redisFailureFallsBackToConfig() {
        when(hashOperations.get(anyString(), anyString())).thenThrow(new RuntimeException("redis down"));

        assertThat(service.isEnabled("checkout-v2")).isTrue();
    }

    @Test
    void userScoped_noRolloutFallsBackToGlobal() {
        assertThat(service.isEnabled("checkout-v2", 1L)).isTrue();
    }

    @Test
    void userScoped_percentBoundsShortCircuitHashing() {
        when(hashOperations.get(anyString(), anyString())).thenReturn(null);

        // global true + percent>=100 -> true; percent<=0 -> false; null user -> global.
        assertThat(service.isEnabled("always", 1L)).isTrue();
        assertThat(service.isEnabled("never", 1L)).isFalse();
        assertThat(service.isEnabled("gradual", null)).isTrue();
    }

    @Test
    void userScoped_hashDecisionIsStableAndWithinPercent() {
        when(hashOperations.get(anyString(), anyString())).thenReturn(null);

        boolean first = service.isEnabled("gradual", 42L);
        for (int i = 0; i < 25; i++) {
            assertThat(service.isEnabled("gradual", 42L)).isEqualTo(first);
        }
    }

    @Test
    void userScoped_hashSplitsPopulationsAroundConfiguredPercent() {
        when(hashOperations.get(anyString(), anyString())).thenReturn(null);

        int enabled = 0;
        for (long id = 1; id <= 200; id++) {
            if (service.isEnabled("gradual", id)) {
                enabled++;
            }
        }
        // 50% rollout: both hash branches must be exercised, roughly half in.
        assertThat(enabled).isBetween(25, 175);
    }

    @Test
    void setFlag_writesThroughRedisAndNotifiesReplicas() {
        service.setFlag("checkout-v2", false);

        verify(hashOperations).put(FeatureFlagService.OVERRIDES_HASH, "checkout-v2", "false");
        verify(redisTemplate).convertAndSend(FeatureFlagService.CHANGED_CHANNEL, "checkout-v2");
        assertThat(service.isEnabled("checkout-v2")).isFalse();
    }

    @Test
    void setFlag_nullDeletesOverrideAndRevertsToConfig() {
        service.setFlag("checkout-v2", true);
        service.setFlag("checkout-v2", null);

        verify(hashOperations).delete(FeatureFlagService.OVERRIDES_HASH, "checkout-v2");
        assertThat(service.isEnabled("checkout-v2")).isTrue(); // config value again
    }

    @Test
    void setFlag_redisFailureStillUpdatesLocalCache() {
        doThrow(new RuntimeException("redis down"))
                .when(hashOperations).put(anyString(), anyString(), anyString());

        service.setFlag("checkout-v2", false);

        assertThat(service.isEnabled("checkout-v2")).isFalse();
        verify(redisTemplate, never()).convertAndSend(anyString(), anyString());
    }

    @Test
    void setFlag_revertWithRedisFailureStillEvictsLocalCache() {
        service.setFlag("checkout-v2", false); // cache: false
        assertThat(service.isEnabled("checkout-v2")).isFalse();

        doThrow(new RuntimeException("redis down"))
                .when(hashOperations).delete(eq(FeatureFlagService.OVERRIDES_HASH), eq("checkout-v2"));

        service.setFlag("checkout-v2", null); // catch branch must still evict

        assertThat(service.isEnabled("checkout-v2")).isTrue(); // back to config value
    }

    @Test
    void snapshot_overlaysRedisOverridesOnConfig() {
        when(hashOperations.entries(FeatureFlagService.OVERRIDES_HASH))
                .thenReturn(Map.of("banners", "true", "checkout-v2", "false"));

        Map<String, Boolean> snapshot = service.snapshot();

        assertThat(snapshot).containsEntry("banners", true).containsEntry("checkout-v2", false);
    }

    @Test
    void snapshot_redisFailureReturnsConfigOnly() {
        when(hashOperations.entries(FeatureFlagService.OVERRIDES_HASH)).thenThrow(new RuntimeException("down"));

        assertThat(service.snapshot()).containsOnly(
                Map.entry("checkout-v2", true), Map.entry("gradual", true),
                Map.entry("always", true), Map.entry("never", true));
    }

    @Test
    void onMessage_evictsCachedKeySoNextReadHitsRedis() {
        when(hashOperations.get(eq(FeatureFlagService.OVERRIDES_HASH), eq("checkout-v2"))).thenReturn(true);
        service.setFlag("checkout-v2", false); // cache now holds the write-through value
        assertThat(service.isEnabled("checkout-v2")).isFalse();

        Message message = org.mockito.Mockito.mock(Message.class);
        when(message.getBody()).thenReturn("checkout-v2".getBytes());
        service.onMessage(message, null);

        // after eviction the next read must go back to Redis and see the new value
        assertThat(service.isEnabled("checkout-v2")).isTrue();
    }

    @Test
    void onMessage_brokenBodyIsSwallowed() {
        Message message = org.mockito.Mockito.mock(Message.class);
        when(message.getBody()).thenThrow(new IllegalStateException("no body"));

        service.onMessage(message, null); // must not propagate
    }
}
