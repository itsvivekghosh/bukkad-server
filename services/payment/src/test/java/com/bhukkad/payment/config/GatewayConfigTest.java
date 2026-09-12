package com.bhukkad.payment.config;

import com.bhukkad.payment.PaymentProperties;
import com.bhukkad.payment.domain.entity.Payment;
import com.bhukkad.payment.domain.repository.PaymentRepository;
import com.bhukkad.payment.infrastructure.client.PaymentGateway;
import com.bhukkad.payment.infrastructure.client.RazorpayPaymentGateway;
import com.bhukkad.payment.infrastructure.client.SimulatedPaymentGateway;
import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.web.reactive.function.client.WebClient;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class GatewayConfigTest {

    private final GatewayConfig config = new GatewayConfig();

    @Test
    void simulatedIsDEFAULTGatewayStrategy() {
        PaymentProperties properties = new PaymentProperties();
        PaymentGateway gateway = config.paymentGateway(
                properties, mock(WebClient.class), id -> null);

        assertThat(gateway).isInstanceOf(SimulatedPaymentGateway.class);
    }

    @Test
    void razorpayStrategySelectedCaseInsensitively() {
        PaymentProperties properties = new PaymentProperties();
        properties.setGateway("RazorPay");

        PaymentGateway gateway = config.paymentGateway(
                properties, mock(WebClient.class), id -> null);

        assertThat(gateway).isInstanceOf(RazorpayPaymentGateway.class);
    }

    @Test
    void paymentRefResolver_readsProviderRefFromPersistedRow() {
        PaymentRepository repository = mock(PaymentRepository.class);
        Payment payment = new Payment();
        payment.setProviderRef("pay_provider_1");
        when(repository.findById(5L)).thenReturn(Optional.of(payment));

        RazorpayPaymentGateway.PaymentRefResolver resolver =
                config.razorpayPaymentRefResolver(repository);

        assertThat(resolver.providerPaymentRef(5L)).isEqualTo("pay_provider_1");
        when(repository.findById(404L)).thenReturn(Optional.empty());
        assertThat(resolver.providerPaymentRef(404L)).isNull();
    }

    @Test
    void razorpayWebClient_buildsWithoutMeterRegistry() {
        @SuppressWarnings("unchecked")
        ObjectProvider<MeterRegistry> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(null);

        assertThat(config.razorpayWebClient(provider)).isNotNull();
    }
}
