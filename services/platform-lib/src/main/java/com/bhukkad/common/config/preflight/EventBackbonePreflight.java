package com.bhukkad.common.config.preflight;

import com.bhukkad.common.kafka.KafkaPlatformEventPublisher;
import com.bhukkad.common.outbox.OutboxPollPublisher;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.EnvironmentAware;
import org.springframework.context.event.EventListener;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.stereotype.Component;

/**
 * Boot preflight for the event backbone (PERF-2/B2, guide §5 step 1, audit
 * G-3 "startup preflight" matrix — events row).
 *
 * <p>Refuses a <strong>prod</strong> startup when the external event pipeline
 * is not fully wired, because every disabled-but-running state in that family
 * silently loses events:</p>
 * <ul>
 *   <li>{@code app.events.external.enabled} not {@code true} — no relay bean,
 *       no Kafka publisher: outbox rows accumulate forever while the service
 *       reports healthy (the shipped base-yml default, called out as B2);</li>
 *   <li>enabled with {@code app.events.external.type} not {@code kafka} —
 *       events go to the log transport only; nothing reaches consumers;</li>
 *   <li>(defence in depth) a relay bean without a Kafka publisher — the exact
 *       relay-on/publisher-off combo that produced the blackhole; it cannot
 *       be created through the shared {@code @ConditionalOnExpression} gate,
 *       but manual {@code @Bean} registrations still exist in the wild.</li>
 * </ul>
 *
 * <p>Non-prod profiles boot untouched, so local/dev/test keep the
 * disabled-by-default ergonomics.</p>
 */
@Slf4j
@Component
public class EventBackbonePreflight implements EnvironmentAware {

    static final String PROD_PROFILE = "prod";
    static final String ENABLED_PROPERTY = "app.events.external.enabled";
    static final String TYPE_PROPERTY = "app.events.external.type";

    private Environment environment;
    private final ObjectProvider<KafkaPlatformEventPublisher> kafkaPublisher;
    private final ObjectProvider<OutboxPollPublisher> relay;

    public EventBackbonePreflight(ObjectProvider<KafkaPlatformEventPublisher> kafkaPublisher,
                                  ObjectProvider<OutboxPollPublisher> relay) {
        this.kafkaPublisher = kafkaPublisher;
        this.relay = relay;
    }

    @Override
    public void setEnvironment(Environment environment) {
        this.environment = environment;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void validate() {
        if (environment == null || !environment.acceptsProfiles(Profiles.of(PROD_PROFILE))) {
            return; // dev/test/local keep the disabled-by-default posture
        }
        boolean enabled = environment.getProperty(ENABLED_PROPERTY, Boolean.class, false);
        String type = environment.getProperty(TYPE_PROPERTY, "log");
        if (!enabled) {
            throw new IllegalStateException(
                    "PERF-2 preflight: refusing prod startup with " + ENABLED_PROPERTY + "=false. "
                            + "External events are the durability backbone: with the pipeline off, "
                            + "outbox rows are never relayed and every cross-service event is silently lost. "
                            + "Set " + ENABLED_PROPERTY + "=true and " + TYPE_PROPERTY + "=kafka in the prod overlay.");
        }
        if (!"kafka".equalsIgnoreCase(type)) {
            throw new IllegalStateException(
                    "PERF-2 preflight: refusing prod startup with " + TYPE_PROPERTY
                            + "=" + type + " (kafka required). The log transport is a dev-only sink; "
                            + "consumers never receive its records.");
        }
        if (relay.getIfAvailable() != null && kafkaPublisher.getIfAvailable() == null) {
            throw new IllegalStateException(
                    "PERF-2 preflight: relay-on/publisher-off detected — an OutboxPollPublisher bean "
                            + "exists without a KafkaPlatformEventPublisher. The two share one gate "
                            + "(app.events.external.enabled + type=kafka); a broken manual wiring would "
                            + "republish the B2 blackhole.");
        }
        log.info("EVENT_BACKBONE_PREFLIGHT_OK | enabled=true | type={}", type);
    }
}
