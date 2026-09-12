package com.bhukkad.order.infrastructure.client;

/** Receipt of a refund ({@code status=REFUNDED}) for the CHARGE_PAYMENT compensation. */
public record RefundResponse(String status) {
}
