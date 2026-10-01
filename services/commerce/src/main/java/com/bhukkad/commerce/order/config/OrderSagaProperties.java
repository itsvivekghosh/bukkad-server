package com.bhukkad.commerce.order.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import com.bhukkad.commerce.order.domain.service.impl.OrderService;

/**
 * Feature #3 asynchronous order saga gate + tuning.
 *
 * <p>{@code enabled} defaults to {@code true}: the asynchronous
 * outbox-driven saga is now the default path (no external I/O inside
 * {@code @Transactional}); the synchronous saga is deprecated.
 * Disable only for emergency rollback via {@code ORDER_ASYNC_SAGA_ENABLED=false}.</p>
 */
@Data
@ConfigurationProperties(prefix = "app.order.async-saga")
public class OrderSagaProperties {

    /** Master gate: true enables async outbox-driven saga (no DB connections held during external calls). */
    private boolean enabled = true;

    /** AWAITING_PAYMENT orders older than this are compensated by the sweep. */
    private int stuckOrderMinutes = 15;

    /** Sweep cadence. */
    private long sweepIntervalMs = 60_000;
}
