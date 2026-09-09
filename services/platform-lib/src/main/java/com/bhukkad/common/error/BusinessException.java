package com.bhukkad.common.error;

/**
 * Base runtime exception for business rule violations. Services may throw
 * subclasses and map them onto {@link ApiError} at the API boundary.
 */
public class BusinessException extends RuntimeException {

    /** Default code used by the message-only constructors (monolith-compatible). */
    public static final String DEFAULT_CODE = "BUSINESS_ERROR";

    private final String code;

    public BusinessException(String message) {
        this(DEFAULT_CODE, message);
    }

    public BusinessException(String message, Throwable cause) {
        this(DEFAULT_CODE, message, cause);
    }

    public BusinessException(String code, String message) {
        super(message);
        this.code = code;
    }

    public BusinessException(String code, String message, Throwable cause) {
        super(message, cause);
        this.code = code;
    }

    public String getCode() {
        return code;
    }
}
