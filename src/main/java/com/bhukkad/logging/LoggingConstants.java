package com.bhukkad.logging;

public final class LoggingConstants {

    // MDC Keys
    public static final String TRACE_ID = "traceId";
    public static final String USER_ID = "userId";
    public static final String USER_EMAIL = "userEmail";
    public static final String USER_ROLE = "userRole";
    public static final String REQUEST_ID = "requestId";
    public static final String REQUEST_PATH = "requestPath";
    public static final String REQUEST_METHOD = "requestMethod";
    public static final String SESSION_ID = "sessionId";
    public static final String IP_ADDRESS = "ipAddress";
    public static final String TIMESTAMP = "timestamp";

    // HTTP headers
    public static final String HEADER_TRACE_ID = "X-Trace-Id";
    public static final String HEADER_REQUEST_ID = "X-Request-Id";
    public static final String HEADER_CORRELATION_ID = "X-Correlation-Id";
    public static final String HEADER_TRACEPARENT = "traceparent";
    public static final String HEADER_EXPOSE = "Access-Control-Expose-Headers";

    // Logger Names
    public static final String PERFORMANCE_LOGGER = "PERFORMANCE";
    public static final String SECURITY_LOGGER = "SECURITY";
    public static final String ORDER_LOGGER = "ORDER";
    public static final String PAYMENT_LOGGER = "PAYMENT";
    public static final String ALERT_LOGGER = "ALERT";

    // Log Events
    public static final String EVENT_USER_LOGIN = "USER_LOGIN";
    public static final String EVENT_USER_LOGOUT = "USER_LOGOUT";
    public static final String EVENT_USER_REGISTER = "USER_REGISTER";
    public static final String EVENT_ORDER_CREATED = "ORDER_CREATED";
    public static final String EVENT_ORDER_UPDATED = "ORDER_UPDATED";
    public static final String EVENT_ORDER_CANCELLED = "ORDER_CANCELLED";
    public static final String EVENT_ORDER_DELIVERED = "ORDER_DELIVERED";
    public static final String EVENT_PAYMENT_INITIATED = "PAYMENT_INITIATED";
    public static final String EVENT_PAYMENT_SUCCESS = "PAYMENT_SUCCESS";
    public static final String EVENT_PAYMENT_FAILED = "PAYMENT_FAILED";
    public static final String EVENT_PAYMENT_REFUNDED = "PAYMENT_REFUNDED";
    public static final String EVENT_RESTAURANT_CREATED = "RESTAURANT_CREATED";
    public static final String EVENT_RESTAURANT_UPDATED = "RESTAURANT_UPDATED";
    public static final String EVENT_MENU_ITEM_CREATED = "MENU_ITEM_CREATED";
    public static final String EVENT_CART_UPDATED = "CART_UPDATED";
    public static final String EVENT_REVIEW_SUBMITTED = "REVIEW_SUBMITTED";
    public static final String EVENT_COUPON_APPLIED = "COUPON_APPLIED";
    public static final String EVENT_UNAUTHORIZED_ACCESS = "UNAUTHORIZED_ACCESS";
    public static final String EVENT_INVALID_TOKEN = "INVALID_TOKEN";

    private LoggingConstants() {}
}