package com.bhukkad.metrics;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

/**
 * Business-level metrics for growth monitoring: order throughput and the
 * order conversion funnel (search -> menu view -> cart add -> checkout ->
 * payment -> delivered).
 */
@Component
public class BusinessMetrics {

    private final Counter funnelSearch;
    private final Counter funnelMenuView;
    private final Counter funnelCartAdd;
    private final Counter funnelCheckout;
    private final Counter funnelPayment;
    private final Counter funnelDelivered;

    public BusinessMetrics(MeterRegistry registry) {
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
}
