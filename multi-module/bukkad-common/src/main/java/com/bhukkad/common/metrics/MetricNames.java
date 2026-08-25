package com.bhukkad.common.metrics;

/**
 * Shared Micrometer meter names so every service exposes the same metric
 * contract and the gateway/dashboards can aggregate across services.
 */
public final class MetricNames {

    private MetricNames() {
    }

    public static final String HTTP_REQUESTS = "bhukkad.http.requests";
    public static final String HTTP_ERRORS = "bhukkad.http.errors";
    public static final String ORDERS_CREATED = "bhukkad.orders.created";
    public static final String ORDERS_DELIVERED = "bhukkad.orders.delivered";
    public static final String SSE_ACTIVE_CONNECTIONS = "sse_active_connections";
    public static final String OUTBOX_PENDING = "bhukkad.outbox.pending";
    public static final String SAGA_FAILED = "bhukkad.saga.failed";

    /** Tag key that carries the owning service name (e.g. "order-service"). */
    public static final String TAG_SERVICE = "service";
}
