package com.bhukkad.common.error;

import org.springframework.http.HttpStatus;

/**
 * Thrown when a request asks for a page size exceeding the platform-wide
 * {@code MAX_PAGE_SIZE} (100) or a batch {@code ids} list exceeding
 * {@code MAX_BATCH_IDS} (100).
 *
 * <p>Mapped to HTTP 400 by {@link com.bhukkad.common.web.GlobalExceptionHandler}
 * via its {@code @ExceptionHandler(PageSizeExceededException.class)} method,
 * so oversized pages are rejected outright instead of being silently clamped,
 * which protects memory and prevents unbounded result-set materialization
 * under fan-out traffic (audit P1-01 / SV-11 / SV-15).
 * <p>
 * Note: no {@code @ResponseStatus} annotation here because {@code
 * com.bhukkad.common.error} must not depend on Spring Web (ArchUnit rule
 * enforced by {@code CommonArchTest}).
 */
public class PageSizeExceededException extends RuntimeException {

    public static final String CODE = "PAGE_SIZE_EXCEEDED";

    private final int requested;
    private final int maxAllowed;

    public PageSizeExceededException(int requested, int maxAllowed) {
        super("Requested page size " + requested + " exceeds maximum allowed page size " + maxAllowed);
        this.requested = requested;
        this.maxAllowed = maxAllowed;
    }

    public int getRequested() {
        return requested;
    }

    public int getMaxAllowed() {
        return maxAllowed;
    }

    @Override
    public String getMessage() {
        return "Page size " + requested + " exceeds maximum " + maxAllowed + " (code: " + CODE + ")";
    }
}
