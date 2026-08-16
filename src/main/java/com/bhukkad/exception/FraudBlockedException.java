package com.bhukkad.exception;

/**
 * Thrown when abuse-velocity enforcement blocks a request.
 *
 * <p>Mapped by {@code GlobalExceptionHandler} to <b>429 Too Many Requests</b> with a
 * {@code Retry-After} header. 429 is deliberate rather than 403: the caller is not permanently
 * forbidden, the same request will succeed once the sliding window drains, and clients already know
 * how to honour {@code Retry-After}.</p>
 *
 * <p>It is intentionally distinct from {@link RateLimitExceededException} even though both surface
 * as 429. Rate limiting keys on the authenticated user or the submitted login email; fraud blocking
 * keys on network origin and device, so the two catch different attacks and are alerted on
 * separately.</p>
 *
 * <p>Messages are kept vague on purpose ("unusual activity") so an attacker cannot use the response
 * to discover which dimension tripped or where the threshold sits. The precise reason is logged
 * server-side.</p>
 */
public class FraudBlockedException extends RuntimeException {

    private final String eventType;
    private final long retryAfterSeconds;

    /**
     * @param message            client-safe message
     * @param eventType          fraud event label that tripped, for logging and alerting
     * @param retryAfterSeconds  seconds until the caller should retry
     */
    public FraudBlockedException(String message, String eventType, long retryAfterSeconds) {
        super(message);
        this.eventType = eventType;
        this.retryAfterSeconds = retryAfterSeconds;
    }

    /** @return fraud event label that tripped the block */
    public String getEventType() {
        return eventType;
    }

    /** @return seconds the caller should wait before retrying */
    public long getRetryAfterSeconds() {
        return retryAfterSeconds;
    }
}
