package com.bhukkad.payment.gateway;

import com.bhukkad.common.web.client.CircuitBreakerFilter;
import com.bhukkad.common.web.client.RetryFilter;
import com.bhukkad.payment.PaymentProperties;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.reactive.function.client.WebClient;

import java.time.Duration;

/**
 * Payment-local WebClient + gateway strategy wiring (audit feature #1).
 *
 * <p>The adapter's WebClient is built from the SAME platform-lib client stack
 * the shared {@code com.bhukkad.common.web.client.WebClientConfig} factory
 * uses — {@code RetryFilter} (transient GETs only) + Resilience4j
 * {@code CircuitBreakerFilter} with meter-exported breaker gauges — so the
 * PSP calls sit behind the platform's breaker/retry discipline. A dedicated
 * {@code razorpay} breaker name keeps PSP failures isolated from the
 * inter-service {@code default} breaker, and an absolute base URL replaces
 * the factory's service-discovery base because Razorpay is an external host,
 * not a mesh service.</p>
 *
 * <p>Strategy selection by properties ({@code app.payment.gateway =
 * razorpay|simulated}); {@code simulated} stays the default so dev/test
 * never touch the real PSP. In prod the overlay must set
 * {@code app.payment.gateway: razorpay} plus the
 * {@code RAZORPAY_KEY_ID}/{@code RAZORPAY_KEY_SECRET}/
 * {@code RAZORPAY_WEBHOOK_SECRET} env vars (guarded by the
 * {@code RazorpayWebhookPreflight}).</p>
 */
@Configuration(proxyBeanMethods = false)
public class GatewayConfig {

    @Bean
    public WebClient razorpayWebClient(ObjectProvider<MeterRegistry> meterRegistryProvider) {
        return WebClient.builder()
                .filter(new RetryFilter(3, Duration.ofSeconds(1)))
                .filter(new CircuitBreakerFilter("razorpay",
                        CircuitBreakerFilter.DEFAULT_CONFIG, meterRegistryProvider.getIfAvailable()))
                .build();
    }

    @Bean
    public RazorpayPaymentGateway.PaymentRefResolver razorpayPaymentRefResolver(
            com.bhukkad.payment.domain.PaymentRepository paymentRepository) {
        // Refund must target the PSP payment id persisted from the charge
        // (providerRef), never the internal numeric id.
        return paymentId -> paymentRepository.findById(paymentId)
                .map(com.bhukkad.payment.domain.Payment::getProviderRef)
                .orElse(null);
    }

    @Bean
    public PaymentGateway paymentGateway(PaymentProperties properties,
                                         WebClient razorpayWebClient,
                                         RazorpayPaymentGateway.PaymentRefResolver refResolver) {
        if ("razorpay".equalsIgnoreCase(properties.getGateway())) {
            return new RazorpayPaymentGateway(razorpayWebClient, properties, refResolver);
        }
        return new SimulatedPaymentGateway();
    }
}
