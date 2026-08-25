package com.bhukkad.event;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Denormalized snapshot of the items in a placed order, published through the
 * outbox as {@code ORDER_ITEMS_SNAPSHOT}. Carries only the fields the ANALYTICS
 * domain needs (menu item id/name, quantity, restaurant id) so the
 * trending-dishes materializer can build its summary table without joining the
 * ORDER and RESTAURANT/MENU schemas.
 */
public record OrderItemsSnapshotEvent(
        Long orderId,
        String orderNumber,
        Long restaurantId,
        List<Item> items,
        LocalDateTime orderedAt
) {
    public record Item(Long menuItemId, String name, Integer quantity) {
    }
}
