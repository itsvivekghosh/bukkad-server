package com.bhukkad.delivery;

import com.bhukkad.common.util.Constants;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Proof-of-delivery tunables: feature is on, enforcement off by default so a
 * backend rollout cannot strand in-flight deliveries.
 */
class DeliveryProofPropertiesTest {

    @Test
    void safeRolloutDefaults() {
        DeliveryProofProperties properties = new DeliveryProofProperties();

        assertThat(properties.isEnabled()).isTrue();
        assertThat(properties.isEnforced()).isFalse();
        assertThat(properties.getOtpExpiryMinutes())
                .isEqualTo(Constants.OTP_EXPIRY_MINUTES).isEqualTo(10);
        assertThat(properties.getMaxOtpAttempts()).isEqualTo(5);
        assertThat(properties.getOtpResendCooldownSeconds()).isEqualTo(60);
    }

    @Test
    void settersRoundTrip() {
        DeliveryProofProperties properties = new DeliveryProofProperties();
        properties.setEnabled(false);
        properties.setEnforced(true);
        properties.setOtpExpiryMinutes(3);
        properties.setMaxOtpAttempts(2);
        properties.setOtpResendCooldownSeconds(30);

        assertThat(properties.isEnabled()).isFalse();
        assertThat(properties.isEnforced()).isTrue();
        assertThat(properties.getOtpExpiryMinutes()).isEqualTo(3);
        assertThat(properties.getMaxOtpAttempts()).isEqualTo(2);
        assertThat(properties.getOtpResendCooldownSeconds()).isEqualTo(30);
    }
}
