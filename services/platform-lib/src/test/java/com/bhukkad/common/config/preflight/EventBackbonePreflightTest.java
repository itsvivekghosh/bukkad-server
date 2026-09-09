package com.bhukkad.common.config.preflight;

import com.bhukkad.common.kafka.KafkaPlatformEventPublisher;
import com.bhukkad.common.outbox.OutboxPollPublisher;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.mock.env.MockEnvironment;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Boot preflight matrix (PERF-2/B2 + audit G-3): prod boots must never leave a
 * blackhole combination live; non-prod keeps the disabled-by-default posture.
 */
class EventBackbonePreflightTest {

    @SuppressWarnings("unchecked")
    private final ObjectProvider<KafkaPlatformEventPublisher> publisherProvider = mock(ObjectProvider.class);
    @SuppressWarnings("unchecked")
    private final ObjectProvider<OutboxPollPublisher> relayProvider = mock(ObjectProvider.class);

    private EventBackbonePreflight preflight(String profiles, String enabled, String type) {
        MockEnvironment env = new MockEnvironment();
        env.setActiveProfiles(profiles.split(","));
        if (enabled != null) {
            env.setProperty("app.events.external.enabled", enabled);
        }
        if (type != null) {
            env.setProperty("app.events.external.type", type);
        }
        EventBackbonePreflight preflight =
                new EventBackbonePreflight(publisherProvider, relayProvider);
        preflight.setEnvironment(env);
        return preflight;
    }

    @Test
    void prodWithEventsDisabled_refusesToBoot() {
        assertThatThrownBy(() -> preflight("prod", "false", "log").validate())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("PERF-2 preflight")
                .hasMessageContaining("app.events.external.enabled=false");
    }

    @Test
    void prodWithMissingEnabledProperty_refusesToBoot() {
        // The SHIPPED BASE-YML DEFAULT — this is the B2 case the audit flagged.
        assertThatThrownBy(() -> preflight("prod", null, null).validate())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("refusing prod startup");
    }

    @Test
    void prodWithLogTransport_refusesToBoot() {
        assertThatThrownBy(() -> preflight("prod", "true", "log").validate())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("kafka required");
    }

    @Test
    void prodWithKafka_passes() {
        when(publisherProvider.getIfAvailable()).thenReturn(mock(KafkaPlatformEventPublisher.class));
        assertThatCode(() -> preflight("prod", "true", "kafka").validate()).doesNotThrowAnyException();
    }

    @Test
    void prodRelayWithoutPublisher_refusedAsBlackholeCombo() {
        // Defence in depth: relay-on / publisher-off cannot come from the shared
        // gate, only from rogue manual bean registration — still refused.
        when(publisherProvider.getIfAvailable()).thenReturn(null);
        when(relayProvider.getIfAvailable()).thenReturn(mock(OutboxPollPublisher.class));
        assertThatThrownBy(() -> preflight("prod", "true", "kafka").validate())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("relay-on/publisher-off");
    }

    @Test
    void devProfile_neverRefuses() {
        assertThatCode(() -> preflight("dev", "false", "log").validate())
                .doesNotThrowAnyException();
        assertThatCode(() -> preflight("local,test", null, null).validate())
                .doesNotThrowAnyException();
    }
}
