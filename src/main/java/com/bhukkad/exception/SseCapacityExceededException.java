package com.bhukkad.exception;

/**
 * Thrown when an SSE stream cannot accept another subscriber because the
 * per-stream emitter capacity has been reached.
 *
 * <p>Each order/restaurant/rider stream has a bounded number of concurrent
 * connections (see {@code app.live.sse.max-emitters-per-stream}). Rejecting
 * new subscribers with a 503 Service Unavailable (rather than silently
 * degrading existing ones) protects pod memory; the client can retry with
 * backoff or fall back to HTTP polling.
 */
public class SseCapacityExceededException extends RuntimeException {

    public SseCapacityExceededException(String message) {
        super(message);
    }
}
