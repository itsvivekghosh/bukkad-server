package com.bhukkad.order.infrastructure.client;

/**
 * One line of a stock reservation exchanged with the restaurant service's
 * internal surface (order saga RESERVE_STOCK step, audit batch A). Mirrors
 * the restaurant service's {@code StockReservationItem} request wire shape
 * ({@code POST /api/v1/inventory/stock-reservation/{reserve,release}}).
 */
public record StockReservationLine(Long menuItemId, String menuItemName, Integer quantity) {

    public static StockReservationLine of(Long menuItemId, String menuItemName, Integer quantity) {
        return new StockReservationLine(menuItemId, menuItemName, quantity);
    }
}
