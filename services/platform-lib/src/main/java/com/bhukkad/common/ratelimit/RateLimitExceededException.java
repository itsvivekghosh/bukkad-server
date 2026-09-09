package com.bhukkad.common.ratelimit;

/**
 * Thrown (via the rate-limit aspect) when a caller exceeds a bucket's limit.
 * Mapped to 429 + Retry-After by {@link com.bhukkad.common.web.GlobalExceptionHandler}.
 */
public class RateLimitExceededException extends RuntimeException {

    private final long retryAfterSeconds;

    public RateLimitExceededException(String message, long retryAfterSeconds) {
        super(message);
        this.retryAfterSeconds = retryAfterSeconds;
    }

    public long getRetryAfterSeconds() {
        return retryAfterSeconds;
    }
}
