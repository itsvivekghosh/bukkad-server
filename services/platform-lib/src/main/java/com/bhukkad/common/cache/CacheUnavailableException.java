package com.bhukkad.common.cache;

/**
 * Thrown when the cache layer (Redis) is unavailable due to circuit breaker open
 * or connection failure. Caller should decide fail-open vs fail-closed based on
 * business criticality.
 */
public class CacheUnavailableException extends RuntimeException {

    public CacheUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }

    public CacheUnavailableException(String message) {
        super(message);
    }
}