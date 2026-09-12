package com.bhukkad.payment.config;

import com.bhukkad.payment.PaymentProperties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class RazorpayWebhookPreflightTest {

    @Mock private Environment environment;

    private RazorpayWebhookPreflight preflight(String webhookSecret) {
        PaymentProperties properties = new PaymentProperties();
        properties.getRazorpay().setWebhookSecret(webhookSecret);
        RazorpayWebhookPreflight preflight = new RazorpayWebhookPreflight(properties);
        preflight.setEnvironment(environment);
        return preflight;
    }

    @Test
    void nonProdProfile_bootsUntouched() {
        when(environment.acceptsProfiles(any(Profiles.class))).thenReturn(false);

        assertThatCode(() -> preflight("dev-webhook-secret").validate())
                .doesNotThrowAnyException();
    }

    @Test
    void prodWithEmptySecret_refusesStartup() {
        when(environment.acceptsProfiles(any(Profiles.class))).thenReturn(true);

        assertThatThrownBy(() -> preflight("").validate())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("empty Razorpay webhook secret");
    }

    @Test
    void prodWithNullSecret_refusesStartup() {
        when(environment.acceptsProfiles(any(Profiles.class))).thenReturn(true);

        assertThatThrownBy(() -> preflight(null).validate())
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void prodWithDevDefaultSecret_refusesStartup() {
        when(environment.acceptsProfiles(any(Profiles.class))).thenReturn(true);

        assertThatThrownBy(() -> preflight(RazorpayWebhookPreflight.DEV_WEBHOOK_SECRET).validate())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("dev-webhook-secret");
    }

    @Test
    void prodWithRealSecret_passes() {
        when(environment.acceptsProfiles(any(Profiles.class))).thenReturn(true);

        assertThatCode(() -> preflight("whsec_rotate_me_daily").validate())
                .doesNotThrowAnyException();
    }

    @Test
    void missingEnvironmentIsIgnored() throws Exception {
        RazorpayWebhookPreflight preflight =
                new RazorpayWebhookPreflight(new PaymentProperties());
        // Without setEnvironment, validate must not explode (unit-test wiring).
        java.lang.reflect.Field f =
                RazorpayWebhookPreflight.class.getDeclaredField("environment");
        f.setAccessible(true);
        assertThat(f.get(preflight)).isNull();
        preflight.validate();
    }
}
