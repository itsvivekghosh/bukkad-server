package com.bhukkad.common.event;

import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifies that {@link EventPlatformConfig} provides a default
 * {@link PlatformEventPublisher} bean when no other publisher is registered.
 */
class EventPlatformConfigTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(EventPlatformConfig.class));

    @Test
    void providesNoOpPublisherByDefault() {
        runner.run(ctx -> {
            PlatformEventPublisher publisher = ctx.getBean(PlatformEventPublisher.class);
            assertThat(publisher).isInstanceOf(NoOpEventPublisher.class);
        });
    }

    @Test
    void usesCustomPublisherWhenAvailable() {
        runner.withBean("customPublisher", PlatformEventPublisher.class, () -> event -> {
        }).run(ctx -> {
            PlatformEventPublisher publisher = ctx.getBean(PlatformEventPublisher.class);
            assertThat(publisher).isNotInstanceOf(NoOpEventPublisher.class);
        });
    }
}