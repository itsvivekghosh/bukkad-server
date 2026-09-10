package com.bhukkad.order;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Feature #3 asynchronous order saga gate + tuning.
 *
 * <p>{@code enabled} defaults to {@code false}: the synchronous
 * {@code OrderService.createOrder} saga (batch A-2025) remains the default
 * path; the prod overlay opts in. See {@code application.yml} →
 * {@code app.order.async-saga}.</p>
 */
@Data
@ConfigurationProperties(prefix = "app.order.async-saga")
public class OrderSagaProperties {

    /** Master gate: false keeps today's synchronous saga (property-gate contract). */
    private boolean enabled = false;

    /** AWAITING_PAYMENT orders older than this are compensated by the sweep. */
    private int stuckOrderMinutes = 15;

    /** Sweep cadence. */
    private long sweepIntervalMs = 60_000;
}
