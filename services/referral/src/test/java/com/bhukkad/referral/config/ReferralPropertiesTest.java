package com.bhukkad.referral.config;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ReferralPropertiesTest {

    @Test
    void serviceProperties_defaults() {
        ReferralServiceProperties properties = new ReferralServiceProperties();

        assertThat(properties.getCodeGenerationAttempts()).isEqualTo(25);
        assertThat(properties.getApplyBonusAmount()).isEqualTo(50.0);
        assertThat(properties.getCompletionBonusAmount()).isEqualTo(25.0);
    }

    @Test
    void serviceProperties_settersRoundTrip() {
        ReferralServiceProperties properties = new ReferralServiceProperties();
        properties.setCodeGenerationAttempts(3);
        properties.setApplyBonusAmount(10.5);
        properties.setCompletionBonusAmount(1.5);

        assertThat(properties.getCodeGenerationAttempts()).isEqualTo(3);
        assertThat(properties.getApplyBonusAmount()).isEqualTo(10.5);
        assertThat(properties.getCompletionBonusAmount()).isEqualTo(1.5);
    }

    @Test
    void abuseProperties_defaultsAndSetters() {
        ReferralAbuseProperties properties = new ReferralAbuseProperties();

        assertThat(properties.getApplyPerCustomerPerDay()).isEqualTo(5);
        assertThat(properties.getApplyPerIpPerDay()).isEqualTo(20);

        properties.setApplyPerCustomerPerDay(7);
        properties.setApplyPerIpPerDay(9);

        assertThat(properties.getApplyPerCustomerPerDay()).isEqualTo(7);
        assertThat(properties.getApplyPerIpPerDay()).isEqualTo(9);
    }
}
