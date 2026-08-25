package com.bhukkad.exception;

/**
 * Thrown when an idempotency key is already being processed by another
 * in-flight request. Mapped to {@code 409 Conflict} ("already processing") per
 * the documented API contract, so clients can surface a non-retryable "duplicate
 * request" state distinct from a 400 validation error.
 */
public class DuplicateRequestException extends BusinessException {

    public DuplicateRequestException(String message) {
        super(message);
    }
}
