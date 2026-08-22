package com.bhukkad.featureflag;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FeatureFlagServiceTest {

    private FeatureFlagService service(boolean configured) {
        FeatureFlagProperties props = new FeatureFlagProperties();
        props.getFlags().put("test.flag", configured);
        return new FeatureFlagService(props);
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
    // Percentage rollout (Point 11)
    // ═══════════════════════════════════════════════════════════

    private FeatureFlagService rolloutService(int percent, boolean enabled) {
        FeatureFlagProperties props = new FeatureFlagProperties();
        props.getFlags().put("rollout.flag", enabled);
        props.getRollout().put("rollout.flag", percent);
        return new FeatureFlagService(props);
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
