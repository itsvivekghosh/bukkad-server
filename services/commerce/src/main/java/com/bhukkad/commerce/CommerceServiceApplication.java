package com.bhukkad.commerce;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.data.jpa.repository.config.EnableJpaAuditing;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import com.bhukkad.commerce.delivery.config.DeliveryEtaProperties;
import com.bhukkad.commerce.delivery.config.DeliveryMatchingProperties;
import com.bhukkad.commerce.delivery.config.DeliveryProofProperties;
import com.bhukkad.commerce.delivery.config.DeliveryTruthProperties;
import com.bhukkad.commerce.delivery.config.GeoMatchingProperties;
import com.bhukkad.commerce.delivery.config.OrderLiveReplayProperties;
import com.bhukkad.commerce.delivery.config.RiderEarningsProperties;
import com.bhukkad.commerce.delivery.config.RiderLocationRetentionProperties;
import com.bhukkad.commerce.delivery.config.RoadDistanceProperties;
import com.bhukkad.commerce.order.config.OrderSagaProperties;
import com.bhukkad.commerce.order.config.ScheduledOrderProperties;
import com.bhukkad.commerce.order.config.SubscriptionProperties;
import com.bhukkad.commerce.payment.PaymentProperties;
import com.bhukkad.commerce.payment.config.SettlementAutomationProperties;

/**
 * Commerce service — consolidated core commerce domain.
 * Owns orders (carts, orders, items, timeline), payments (charges, wallets, webhooks),
 * and delivery (riders, dispatch, ETA, proofs).
 * Consolidated from order + payment + delivery services.
 */
@SpringBootApplication(scanBasePackages = {"com.bhukkad.commerce", "com.bhukkad.common"})
@EntityScan(basePackages = {"com.bhukkad.commerce", "com.bhukkad.common"})
@EnableJpaRepositories(basePackages = {"com.bhukkad.commerce", "com.bhukkad.common"})
@EnableJpaAuditing
@EnableConfigurationProperties({
        DeliveryEtaProperties.class,
        DeliveryMatchingProperties.class,
        DeliveryProofProperties.class,
        DeliveryTruthProperties.class,
        GeoMatchingProperties.class,
        OrderLiveReplayProperties.class,
        OrderSagaProperties.class,
        PaymentProperties.class,
        RiderEarningsProperties.class,
        RiderLocationRetentionProperties.class,
        RoadDistanceProperties.class,
        ScheduledOrderProperties.class,
        SettlementAutomationProperties.class,
        SubscriptionProperties.class
})
public class CommerceServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(CommerceServiceApplication.class, args);
    }
}