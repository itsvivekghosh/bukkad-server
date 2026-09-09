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
 * payment -> delivered).
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
