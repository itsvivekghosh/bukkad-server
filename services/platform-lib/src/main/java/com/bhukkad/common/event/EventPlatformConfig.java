package com.bhukkad.common.event;

import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Wires the default {@link PlatformEventPublisher} bean.
 *
 * <p>Every service gets a working {@code PlatformEventPublisher} so saga
 * coordinators and other domain components can publish events regardless of
 * the configured transport. By default a {@link NoOpEventPublisher} is
 * registered; services that wire Kafka/outbox publishing supply their own
 * {@code PlatformEventPublisher} bean, which takes precedence via
 * {@link ConditionalOnMissingBean}.</p>
 */
@Configuration(proxyBeanMethods = false)
public class EventPlatformConfig {

    @Bean
    @ConditionalOnMissingBean(PlatformEventPublisher.class)
    public PlatformEventPublisher platformEventPublisher() {
        return new NoOpEventPublisher();
    }
}
