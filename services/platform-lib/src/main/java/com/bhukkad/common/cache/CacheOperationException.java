package com.bhukkad.common.cache;

/**
 * Thrown when a cache operation fails due to an unexpected error (bug, serialization issue, etc.).
 * Unlike {@link CacheUnavailableException}, this indicates a problem in the cache logic itself,
 * not infrastructure unavailability. Caller should typically fail-closed and alert.
 */
public class CacheOperationException extends RuntimeException {

    public CacheOperationException(String message, Throwable cause) {
        super(message, cause);
    }

    public CacheOperationException(String message) {
        super(message);
    }
}