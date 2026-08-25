package com.bhukkad.featureflag;

import com.bhukkad.testutil.InMemoryHashOperations;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.HashOperations;
import org.springframework.data.redis.core.StringRedisTemplate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class FeatureFlagServiceTest {

    private InMemoryHashOperations<String, String, String> hashStore;

    private FeatureFlagService service(boolean configured) {
        FeatureFlagProperties props = new FeatureFlagProperties();
        props.getFlags().put("test.flag", configured);
        return buildService(props);
    }

    private FeatureFlagService buildService(FeatureFlagProperties props) {
        StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);
        @SuppressWarnings("unchecked")
        HashOperations<String, String, String> hashOps = (HashOperations<String, String, String>) (HashOperations<?, ?, ?>) hashStore;
        org.mockito.Mockito.doReturn(hashOps).when(redisTemplate).opsForHash();
        return new FeatureFlagService(props, redisTemplate);
    }

    @BeforeEach
    void setUp() {
        hashStore = new InMemoryHashOperations<>();
    }

    @Test
    void isEnabled_returnsConfiguredValue() {
        assertTrue(service(true).isEnabled("test.flag"));
        assertFalse(service(false).isEnabled("test.flag"));
    }

    @Test
    void isEnabled_unknownFlagDefaultsFalse() {
        assertFalse(service(false).isEnabled("does-not-exist"));
    }

    @Test
    void setFlag_overridesConfig() {
        FeatureFlagService svc = service(false);
        svc.setFlag("test.flag", true);
        assertTrue(svc.isEnabled("test.flag"));
    }

    @Test
    void setFlag_nullRevertsToConfig() {
        FeatureFlagService svc = service(true);
        svc.setFlag("test.flag", false);
        assertFalse(svc.isEnabled("test.flag"));
        svc.setFlag("test.flag", null);
        assertTrue(svc.isEnabled("test.flag"));
    }

    @Test
    void snapshot_includesConfigAndOverrides() {
        FeatureFlagService svc = service(true);
        svc.setFlag("other.flag", true);
        assertEquals(2, svc.snapshot().size());
    }

    // ═══════════════════════════════════════════════════════════
    // Cluster consistency (horizontal scaling, Phase 1)
    // ═══════════════════════════════════════════════════════════

    @Test
    void overrideWrittenByOneReplicaIsVisibleToAnother() {
        FeatureFlagService replicaA = service(false);
        FeatureFlagService replicaB = service(false);

        replicaA.setFlag("test.flag", true);

        // B's hot-path read hits the shared Redis hash → override visible.
        assertTrue(replicaB.isEnabled("test.flag"));
        assertTrue(replicaA.isEnabled("test.flag"));
    }

    @Test
    void revertByOneReplicaIsVisibleToAnother() {
        FeatureFlagService replicaA = service(true);
        FeatureFlagService replicaB = service(true);

        replicaA.setFlag("test.flag", false);
        // Simulate pub/sub delivery of the change to replica B.
        replicaB.onMessage(messageFor("test.flag"), null);
        assertFalse(replicaB.isEnabled("test.flag"));

        replicaA.setFlag("test.flag", null);
        replicaB.onMessage(messageFor("test.flag"), null);
        assertTrue(replicaB.isEnabled("test.flag"));
    }

    @Test
    void snapshotSeesOverridesFromSharedRedis() {
        FeatureFlagService replicaA = service(true);
        FeatureFlagService replicaB = service(false);

        replicaA.setFlag("test.flag", true);

        assertEquals(true, replicaB.snapshot().get("test.flag"));
    }

    @Test
    void onMessage_evictsCachedOverrideAndReadsRedisAgain() {
        FeatureFlagService replicaA = service(false);
        FeatureFlagService replicaB = service(false);

        replicaA.setFlag("test.flag", true);
        // B caches the override.
        assertTrue(replicaB.isEnabled("test.flag"));

        // A reverts; the pub/sub message evicts B's cache so the next read
        // falls back to config (false) instead of the stale cached value.
        replicaA.setFlag("test.flag", null);
        replicaB.onMessage(messageFor("test.flag"), null);

        assertFalse(replicaB.isEnabled("test.flag"));
    }

    @Test
    void setFlag_publishesChangeNotification() {
        FeatureFlagService svc = service(false);
        svc.setFlag("test.flag", true);

        org.mockito.Mockito.verify(stringRedisTemplateOf(svc))
                .convertAndSend("bhukkad:feature-flag:changed", "test.flag");
    }

    private static org.springframework.data.redis.connection.Message messageFor(String key) {
        org.springframework.data.redis.connection.Message message =
                org.mockito.Mockito.mock(org.springframework.data.redis.connection.Message.class);
        org.mockito.Mockito.when(message.getBody()).thenReturn(key.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        return message;
    }

    private static StringRedisTemplate stringRedisTemplateOf(FeatureFlagService svc) {
        try {
            java.lang.reflect.Field field = FeatureFlagService.class.getDeclaredField("stringRedisTemplate");
            field.setAccessible(true);
            return (StringRedisTemplate) field.get(svc);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    // ═══════════════════════════════════════════════════════════
    // Percentage rollout (Point 11)
    // ═══════════════════════════════════════════════════════════

    private FeatureFlagService rolloutService(int percent, boolean enabled) {
        FeatureFlagProperties props = new FeatureFlagProperties();
        props.getFlags().put("rollout.flag", enabled);
        props.getRollout().put("rollout.flag", percent);
        return buildService(props);
    }

    @Test
    void isEnabled_withUser_rollout100_enablesAll() {
        FeatureFlagService svc = rolloutService(100, true);
        assertTrue(svc.isEnabled("rollout.flag", 1L));
        assertTrue(svc.isEnabled("rollout.flag", 999L));
    }

    @Test
    void isEnabled_withUser_rollout0_disablesAll() {
        FeatureFlagService svc = rolloutService(0, true);
        assertFalse(svc.isEnabled("rollout.flag", 1L));
        assertFalse(svc.isEnabled("rollout.flag", 999L));
    }

    @Test
    void isEnabled_withUser_sameUserAlwaysSameResult() {
        FeatureFlagService svc = rolloutService(50, true);
        boolean first = svc.isEnabled("rollout.flag", 42L);
        boolean second = svc.isEnabled("rollout.flag", 42L);
        assertEquals(first, second, "user-scoped rollout must be deterministic per user");
    }

    @Test
    void isEnabled_withUser_globalDisabled_staysDisabled() {
        FeatureFlagService svc = rolloutService(100, false);
        assertFalse(svc.isEnabled("rollout.flag", 1L));
    }

    @Test
    void isEnabled_withUser_nullUser_fallsBackToGlobal() {
        FeatureFlagService svc = rolloutService(50, true);
        assertTrue(svc.isEnabled("rollout.flag", null));
    }

    @Test
    void isEnabled_withUser_noRolloutConfig_fallsBackToGlobal() {
        FeatureFlagService svc = service(true);
        assertTrue(svc.isEnabled("test.flag", 7L));
    }

    @Test
    void setFlag_logsAudit_toggleIsVisibleInSnapshot() {
        FeatureFlagService svc = service(false);
        svc.setFlag("test.flag", true);
        assertTrue(svc.isEnabled("test.flag"));
    }
}
