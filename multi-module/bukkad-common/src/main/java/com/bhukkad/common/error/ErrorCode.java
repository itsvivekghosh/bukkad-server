package com.bhukkad.common.error;

/**
 * Canonical error codes shared across services so the gateway can translate
 * service errors into a uniform API error envelope without leaking internals.
 */
public enum ErrorCode {
    VALIDATION_ERROR,
    NOT_FOUND,
    CONFLICT,
    UNAUTHORIZED,
    FORBIDDEN,
    RATE_LIMITED,
    PAYMENT_FAILED,
    ORDER_NOT_ELIGIBLE,
    SERVICE_UNAVAILABLE,
    INTERNAL
}
