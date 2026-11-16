package com.bhukkad.common.web.client;

import org.springframework.boot.context.properties.ConfigurationProperties;
import java.time.Duration;

/**
 * Configuration for WebClient connection pool sizes.
 *
 * <p>Allows operators to tune per-target connection pool sizes based on
 * observed traffic patterns. Defaults match the previous hardcoded values
 * for backward compatibility.</p>
 */
@ConfigurationProperties(prefix = "app.webclient.pool")
public class WebClientPoolProperties {

    private int sharedMax = 256;
    private int paymentMax = 100;
    private int orderMax = 100;
    private int deliveryMax = 100;
    private int defaultMax = 64;
    private Duration pendingAcquireTimeout = Duration.ofSeconds(3);
    private Duration connectTimeout = Duration.ofSeconds(2);
    private Duration responseTimeout = Duration.ofSeconds(5);

    public int getSharedMax() { return sharedMax; }
    public void setSharedMax(int sharedMax) { this.sharedMax = sharedMax; }

    public int getPaymentMax() { return paymentMax; }
    public void setPaymentMax(int paymentMax) { this.paymentMax = paymentMax; }

    public int getOrderMax() { return orderMax; }
    public void setOrderMax(int orderMax) { this.orderMax = orderMax; }

    public int getDeliveryMax() { return deliveryMax; }
    public void setDeliveryMax(int deliveryMax) { this.deliveryMax = deliveryMax; }

    public int getDefaultMax() { return defaultMax; }
    public void setDefaultMax(int defaultMax) { this.defaultMax = defaultMax; }

    public Duration getPendingAcquireTimeout() { return pendingAcquireTimeout; }
    public void setPendingAcquireTimeout(Duration pendingAcquireTimeout) { this.pendingAcquireTimeout = pendingAcquireTimeout; }

    public Duration getConnectTimeout() { return connectTimeout; }
    public void setConnectTimeout(Duration connectTimeout) { this.connectTimeout = connectTimeout; }

    public Duration getResponseTimeout() { return responseTimeout; }
    public void setResponseTimeout(Duration responseTimeout) { this.responseTimeout = responseTimeout; }
}
