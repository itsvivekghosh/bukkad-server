package com.bhukkad.dto.response;

/**
 * A menu item that is currently trending (high order-item volume in the last
 * hour), surfaced on the customer home feed.
 *
 * @param id             menu item id
 * @param name           menu item name
 * @param orderItemCount number of order items for the dish in the window
 */
public record TrendingDishResponse(
        Long id,
        String name,
        Long orderItemCount
) {}
