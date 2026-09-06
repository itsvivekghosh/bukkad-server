package com.bhukkad.common.outbox;

import com.bhukkad.common.kafka.KafkaPlatformEventPublisher;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Wires the outbox relay + its configuration. Only active when the external
 * event pipeline is enabled ({@code app.events.external.enabled=true}), which
 * is the same gate {@link com.bhukkad.common.kafka.KafkaPlatformConfig} uses to
 * create the {@link KafkaPlatformEventPublisher}. When disabled, no poller
 * bean exists and PENDING rows simply accumulate (acceptable for local/dev runs
 * without a broker).
 *
 * <p>{@code OutboxProperties} is always registered (via
 * {@link EnableConfigurationProperties}) so the
 * {@code fixedDelayString} SpEL on {@link OutboxPollPublisher#poll()} resolves
 * even in contexts that do not start the poller.</p>
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "app.events.external.enabled", havingValue = "true")
public class OutboxPlatformConfig {

    @Bean
    @ConditionalOnMissingBean
    public OutboxPollPublisher outboxPollPublisher(OutboxEventRepository repository,
                                                   KafkaPlatformEventPublisher publisher,
                                                   OutboxProperties properties) {
        return new OutboxPollPublisher(repository, publisher, properties);
    }
}
