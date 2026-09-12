package com.bhukkad.admin.config;

import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class FraudAndScoringPropertiesTest {

    @Test
    void fraudProperties_thresholdFor_prefersConfiguredThresholds() {
        FraudProperties properties = new FraudProperties();
        FraudProperties.Threshold custom = new FraudProperties.Threshold(3, 4);
        Map<String, FraudProperties.Threshold> thresholds = new HashMap<>();
        thresholds.put("LOGIN", custom);
        properties.setThresholds(thresholds);

        assertThat(properties.thresholdFor("LOGIN")).isSameAs(custom);
    }

    @Test
    void fraudProperties_thresholdFor_fallsBackToBuiltInDefaults() {
        FraudProperties properties = new FraudProperties();

        FraudProperties.Threshold register = properties.thresholdFor("AUTH_REGISTER");
        assertThat(register.getPerIp()).isEqualTo(10);
        assertThat(register.getPerDevice()).isEqualTo(5);
        assertThat(properties.thresholdFor("AUTH_LOGIN").getPerIp()).isEqualTo(40);
        assertThat(properties.thresholdFor("ORDER_CREATE").getPerIp()).isEqualTo(25);
    }

    @Test
    void fraudProperties_unknownEventType_getsGlobalPairInsteadOfNull() {
        FraudProperties properties = new FraudProperties();

        FraudProperties.Threshold threshold = properties.thresholdFor("SOMETHING_NEW");
        assertThat(threshold).isNotNull();
        assertThat(threshold.getPerIp()).isGreaterThanOrEqualTo(0);
        assertThat(threshold.getPerDevice()).isGreaterThanOrEqualTo(0);
    }

    @Test
    void threshold_emptyCtorThenSetters() {
        FraudProperties.Threshold threshold = new FraudProperties.Threshold();
        threshold.setPerIp(7);
        threshold.setPerDevice(9);

        assertThat(threshold.getPerIp()).isEqualTo(7);
        assertThat(threshold.getPerDevice()).isEqualTo(9);
    }

    @Test
    void featureFlags_rolloutLookup() {
        FeatureFlagProperties properties = new FeatureFlagProperties();
        properties.getRollout().put("canary", 25);

        assertThat(properties.getRolloutPercent("canary")).isEqualTo(25);
        assertThat(properties.getRolloutPercent("absent")).isNull();
        assertThat(properties.isEnabled("absent")).isFalse();
    }

    @Test
    void fraudScoring_weightLookups() {
        FraudScoringProperties properties = new FraudScoringProperties();

        assertThat(properties.weight("does-not-exist")).isZero();
        properties.getWeights().forEach((k, v) -> assertThat(properties.weight(k)).isEqualTo(v));
    }
}
