package com.bhukkad.commerce.order.api.dto.response;

import java.util.List;

/**
 * Paginated list of orders with cursor-based pagination.
 *
 * <p>Clients use {@code nextCursor} to fetch subsequent pages.
 * When {@code nextCursor} is null, there are no more pages.</p>
 *
 * @param content   the orders on the current page
 * @param nextCursor opaque cursor token for the next page, or null if no more pages
 * @param hasMore   whether additional pages exist
 */
public record OrderListResponse(
    List<OrderResponse> content,
    String nextCursor,
    boolean hasMore
) {}
