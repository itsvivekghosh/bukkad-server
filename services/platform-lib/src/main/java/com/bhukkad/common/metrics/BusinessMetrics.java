package com.bhukkad.common.metrics;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.stereotype.Component;

import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

/**
 * Business-level metrics for growth monitoring: order throughput and the
 * order conversion funnel (search -> menu view -> cart add -> checkout ->
 * payment -> delivered), plus the real-time operational analytics events
 * (order created, payment completed, delivery assigned, fraud blocked) and
 * the gateway latency histogram that feed the analytics dashboards and
 * alerts in {@code k8s/analytics} and {@code k8s/monitoring}.
 *
 * <p>Metric names (Prometheus renders {@code .} as {@code _}):</p>
 * <ul>
 *   <li>{@code bhukkad.business.orders.created} — counter, order created via outbox publish;</li>
 *   <li>{@code bhukkad.business.payments.completed} — counter, payment completed via outbox publish;</li>
 *   <li>{@code bhukkad.business.deliveries.assigned} — counter, delivery assigned via outbox publish;</li>
 *   <li>{@code bhukkad.business.fraud.blocked} — counter, fraud block via outbox publish;</li>
 *   <li>{@code bhukkad.business.gateway.latency} — timer (ms), gateway request latency.</li>
 * </ul>
 */
@Component
public class BusinessMetrics {

    private final MeterRegistry registry;
    private final Counter funnelSearch;
    private final Counter funnelMenuView;
    private final Counter funnelCartAdd;
    private final Counter funnelCheckout;
    private final Counter funnelPayment;
    private final Counter funnelDelivered;
    private final Counter ordersCreated;
    private final Counter paymentsCompleted;
    private final Counter deliveriesAssigned;
    private final Counter fraudBlocked;
    private final Timer gatewayLatency;

    public BusinessMetrics(MeterRegistry registry) {
        this.registry = registry;
        funnelSearch = Counter.builder("bhukkad.funnel.search")
                .description("Users performing a search").register(registry);
        funnelMenuView = Counter.builder("bhukkad.funnel.menu_view")
                .description("Users viewing a restaurant menu").register(registry);
        funnelCartAdd = Counter.builder("bhukkad.funnel.cart_add")
                .description("Items added to cart").register(registry);
        funnelCheckout = Counter.builder("bhukkad.funnel.checkout")
                .description("Orders reaching checkout").register(registry);
        funnelPayment = Counter.builder("bhukkad.funnel.payment")
                .description("Orders with a successful payment").register(registry);
        funnelDelivered = Counter.builder("bhukkad.funnel.delivered")
                .description("Orders delivered").register(registry);
        ordersCreated = Counter.builder("bhukkad.business.orders.created")
                .description("Orders created (OrderCreated event published via Kafka outbox)").register(registry);
        paymentsCompleted = Counter.builder("bhukkad.business.payments.completed")
                .description("Payments completed (PaymentCompleted event published via Kafka outbox)").register(registry);
        deliveriesAssigned = Counter.builder("bhukkad.business.deliveries.assigned")
                .description("Deliveries assigned (DeliveryAssigned event published via Kafka outbox)").register(registry);
        fraudBlocked = Counter.builder("bhukkad.business.fraud.blocked")
                .description("Fraud blocks (FraudBlocked event published via Kafka outbox)").register(registry);
        gatewayLatency = Timer.builder("bhukkad.business.gateway.latency")
                .description("Gateway request latency histogram (base unit ms)")
                .register(registry);
    }

    public void search() {
        funnelSearch.increment();
    }

    public void menuView() {
        funnelMenuView.increment();
    }

    public void cartAdd() {
        funnelCartAdd.increment();
    }

    public void checkout() {
        funnelCheckout.increment();
    }

    public void payment() {
        funnelPayment.increment();
    }

    public void delivered() {
        funnelDelivered.increment();
    }

    /** Increments the orders-created counter; called on acked {@code OrderCreated} publish. */
    public void recordOrderCreated() {
        ordersCreated.increment();
    }

    /** Increments the payments-completed counter; called on acked {@code PaymentCompleted} publish. */
    public void recordPaymentCompleted() {
        paymentsCompleted.increment();
    }

    /** Increments the deliveries-assigned counter; called on acked {@code DeliveryAssigned} publish. */
    public void recordDeliveryAssigned() {
        deliveriesAssigned.increment();
    }

    /** Increments the fraud-blocked counter; called on acked {@code FraudBlocked} publish. */
    public void recordFraudBlocked() {
        fraudBlocked.increment();
    }

    /** Records a gateway request latency sample (milliseconds). */
    public void recordGatewayLatency(long durationMillis) {
        gatewayLatency.record(durationMillis, TimeUnit.MILLISECONDS);
    }

    // ==================== Generic metrics API ====================

    /**
     * Increments a tagged counter, registering it lazily on first use.
     *
     * <p>Tag key/value pairs must be supplied as alternating arguments
     * (e.g. {@code increment("orders.created", "status", "settled")}); an
     * odd number of arguments throws {@link IllegalArgumentException}.</p>
     */
    public void increment(String name, String... tags) {
        validateTagPairs(tags);
        Counter counter = Counter.builder(name).tags(tags).register(registry);
        counter.increment();
    }

    /**
     * Records a value in a {@link Timer} under {@code name}. Multiple calls
     * accumulate samples; the resulting histogram is exported as a Prometheus
     * timer with the supplied tag key/value pairs.
     */
    public void record(String name, long durationMillis, String... tags) {
        validateTagPairs(tags);
        Timer timer = Timer.builder(name).tags(tags).register(registry);
        timer.record(durationMillis, TimeUnit.MILLISECONDS);
    }

    /**
     * Wraps a {@link Supplier} in a timer, recording the elapsed wall-clock
     * time and returning the supplier's result. The supplier is always invoked
     * exactly once; timing is best-effort (a Stopwatch exception is logged
     * and swallowed so the caller's logic is never broken by a metric failure).
     */
    public <T> T timed(String name, Supplier<T> work, String... tags) {
        validateTagPairs(tags);
        Timer timer = Timer.builder(name).tags(tags).register(registry);
        return timer.record(work);
    }

    private static void validateTagPairs(String... tags) {
        if (tags == null || tags.length % 2 != 0) {
            throw new IllegalArgumentException(
                    "Tags must be supplied as alternating key/value pairs; got " +
                            (tags == null ? 0 : tags.length) + " argument(s)");
        }
    }
}
