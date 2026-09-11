package com.bhukkad.payment.gateway;

import com.bhukkad.common.web.client.PlatformWebClientBuilderFactory;
import com.bhukkad.payment.PaymentProperties;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.reactive.function.client.WebClient;

/**
 * Payment-local WebClient + gateway strategy wiring (audit feature #1).
 *
 * <p>The adapter's WebClient comes from {@link PlatformWebClientBuilderFactory}
 * (audit G-13/P-05, mirroring notification's TwilioSmsSender): the bounded
 * JVM-wide connection pool, explicit timeouts, transient-only idempotent
 * {@code RetryFilter} and a Resilience4j {@code CircuitBreakerFilter} with
 * meter-exported gauges. The dedicated {@code razorpay} breaker target keeps
 * PSP failures isolated from the inter-service {@code default} breaker, and
 * the absolute Razorpay base URL replaces the factory's host-less base because
 * Razorpay is an external host, not a mesh service (added on the copied
 * builder via {@code mutate()}; Spring 6.1 copies connector + filters and
 * permits the baseUrl change — revisit this pin on a Boot 3.5/6.2 upgrade,
 * where mutating the base URL is restricted).</p>
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
    public WebClient razorpayWebClient(PaymentProperties properties,
                                       ObjectProvider<MeterRegistry> meterRegistryProvider) {
        return PlatformWebClientBuilderFactory
                .forTarget("razorpay", meterRegistryProvider.getIfAvailable())
                .build()
                .mutate()
                .baseUrl(properties.getRazorpay().getBaseUrl())
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
