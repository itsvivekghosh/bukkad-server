package com.bhukkad.restaurant.service.inventory;

/** Reservation line as the service layer knows it (constraints live at the API boundary). */
public record StockReservationItem(Long menuItemId, String menuItemName, Integer quantity) {
}
