package com.bhukkad.common.event;

import java.time.Instant;

/**
 * Restaurant domain events.
 */
public final class RestaurantEvents {
    private RestaurantEvents() {
    }

    public record RestaurantCreated(Long restaurantId, Long ownerId, String name, Instant occurredAt)
            implements PlatformEvent {
        @Override
        public Long aggregateId() {
            return restaurantId;
        }

        @Override
        public String eventType() {
            return "RestaurantCreated";
        }

        @Override
        public Object payload() {
            return this;
        }
    }

    public record MenuItemUpdated(Long menuItemId, Long restaurantId, Double price, Boolean available, Instant occurredAt)
            implements PlatformEvent {
        @Override
        public Long aggregateId() {
            return menuItemId;
        }

        @Override
        public String eventType() {
            return "MenuItemUpdated";
        }

        @Override
        public Object payload() {
            return this;
        }
    }
}
