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
}
