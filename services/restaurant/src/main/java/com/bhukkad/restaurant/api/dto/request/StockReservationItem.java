package com.bhukkad.restaurant.api.dto.request;

/** Reservation line as the service layer knows it (constraints live at the API boundary). */
public record StockReservationItem(Long menuItemId, String menuItemName, Integer quantity) {
}
