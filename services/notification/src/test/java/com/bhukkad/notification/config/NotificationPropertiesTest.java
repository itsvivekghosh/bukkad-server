package com.bhukkad.notification.config;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class NotificationPropertiesTest {

    @Test
    void defaultsAreSensible() {
        NotificationProperties props = new NotificationProperties();
        assertThat(props.isEnabled()).isTrue();
        assertThat(props.getEmail().isEnabled()).isFalse();
        assertThat(props.getEmail().getFrom()).isEqualTo("noreply@bhukkad.com");
        assertThat(props.getSms().getProvider()).isEqualTo("log");
        assertThat(props.getWhatsapp().getProvider()).isEqualTo("log");
        assertThat(props.getPush().getProvider()).isEqualTo("log");
    }

    @Test
    void twilioDefaultsAreEmptyStrings() {
        NotificationProperties.Twilio twilio = new NotificationProperties.Twilio();
        assertThat(twilio.getAccountSid()).isEmpty();
        assertThat(twilio.getAuthToken()).isEmpty();
        assertThat(twilio.getFromNumber()).isEmpty();
        assertThat(twilio.getWhatsappFromNumber()).isEmpty();
    }
}
