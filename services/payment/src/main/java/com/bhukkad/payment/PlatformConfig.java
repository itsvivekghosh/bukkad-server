package com.bhukkad.payment;

import com.bhukkad.common.outbox.OutboxClient;
import com.bhukkad.common.outbox.OutboxEventRepository;
import com.bhukkad.payment.service.PaymentService;
import com.bhukkad.payment.settlement.SettlementAutomationProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Payment service platform wiring.
 *
 * <p>{@code @EnableScheduling} is required here and in every outbox-producing
 * service: the platform-lib {@code OutboxPollPublisher} relay is an
 * {@code @Scheduled} ticker (delivery and order enable it in their
 * equivalents; payment was missing it at the split, which silently froze all
 * payment outbox events in the DB).</p>
 */
@Configuration
@EnableScheduling
@EnableConfigurationProperties({SettlementAutomationProperties.class, PaymentProperties.class})
public class PlatformConfig {

    @Bean
    public OutboxClient outboxClient(OutboxEventRepository outboxEventRepository) {
        return new OutboxClient(outboxEventRepository);
    }

    /** Currency source for the charge path (keeps PaymentService decoupled from the full properties). */
    @Bean
    public PaymentService.PaymentPropertiesGateway paymentCurrencyGateway(PaymentProperties properties) {
        return properties.getRazorpay()::getCurrency;
    }
}
