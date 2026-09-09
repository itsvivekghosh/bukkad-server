package com.bhukkad.common.event;

/**
 * No-op event publisher for services that don't need event publishing
 * (e.g., during local development or in tests).
 */
public class NoOpEventPublisher implements PlatformEventPublisher {
    @Override
    public void publish(PlatformEvent event) {
        // No-op
    }
}
