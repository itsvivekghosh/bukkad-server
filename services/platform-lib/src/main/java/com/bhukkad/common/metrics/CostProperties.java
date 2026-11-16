package com.bhukkad.common.metrics;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Pricing and tuning constants for cost attribution (architecture-audit.md §3.4).
 *
 * <p>All monetary values are in USD. The defaults are illustrative placeholders
 * and MUST be overridden per environment/cloud provider via
 * {@code app.cost.*} (e.g. {@code app.cost.cost-per-cpu-ms-usd}) because actual
 * blended rates depend on the committed-use discounts, instance family and
 * region each service runs in. They are intentionally not hardcoded in the
 * metric-emitting code so operators can reprice without a rebuild.</p>
 */
@Data
@ConfigurationProperties(prefix = "app.cost")
public class CostProperties {

    /** Whether per-request cost attribution is enabled. */
    private boolean enabled = true;

    /** Tumbling aggregation window in seconds (default 5 minutes). */
    private int aggregationWindowSeconds = 300;

    /**
     * Blended cost per millisecond of CPU time (USD/ms).
     *
     * <p>Derived from the effective vCPU/hour rate: {@code costPerCpuMsUsd =
     * vcpuHourlyUsd / 3_600_000}.</p>
     */
    private double costPerCpuMsUsd = 0.000_000_01;

    /**
     * Blended cost per megabyte of allocated memory (USD/MB).
     *
     * <p>Derived from the effective memory/GB/hour rate:
     * {@code costPerMemMbUsd = gbHourlyUsd / 1024}.</p>
     */
    private double costPerMemMbUsd = 0.000_000_01;

    /**
     * Blended cost per gigabyte of network egress (USD/GB).
     */
    private double costPerNetGbUsd = 0.09;

    /** Fallback tenant tag when no tenant is present in MDC. */
    private String unknownTenant = "unknown";

    /** Fallback service tag when MDC carries no service name. */
    private String unknownService = "unknown";
}
