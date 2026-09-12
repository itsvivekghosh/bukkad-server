package com.bhukkad.payment.domain.service;

import com.bhukkad.common.event.PlatformEventMessage;
import com.bhukkad.common.outbox.OutboxClient;
import com.bhukkad.payment.domain.entity.Payment;
import com.bhukkad.payment.domain.repository.PaymentRepository;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;

import java.math.BigDecimal;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Legal-transition matrix for the webhook path (feature #1/D3): the
 * conditional {@code UPDATE payments … WHERE status = :expected} only fires on
 * legal (from → to) pairs; every illegal pair is a no-op that increments the
 * {@code payment.webhook.illegal.transitions} metric and enqueues nothing.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class WebhookTransitionMatrixTest {

    private static final String PROVIDER_REF = "pay_1";
    private static final String GATEWAY_ORDER = "order_1";

    @Mock private PaymentRepository paymentRepository;
    @Mock private WalletService walletService;
    @Mock private com.bhukkad.common.idempotency.IdempotencyRecordRepository idempotencyRepository;
    @Mock private com.bhukkad.payment.infrastructure.persistence.PaymentScopeIdempotencyRepository paymentScopeIdempotencyRepository;
    @Mock private OutboxClient outboxClient;
    @Mock private com.bhukkad.payment.infrastructure.client.PaymentGateway paymentGateway;
    @Mock private PaymentService.PaymentPropertiesGateway currencyResolver;
    @Mock private PlatformTransactionManager transactionManager;
    @Mock private ObjectProvider<MeterRegistry> meterRegistryProvider;

    private PaymentService service;
    private SimpleMeterRegistry meterRegistry;

    @BeforeEach
    void setUp() {
        lenient().when(currencyResolver.currency()).thenReturn("INR");
        lenient().when(transactionManager.getTransaction(any()))
                .thenReturn(new SimpleTransactionStatus());
        meterRegistry = new SimpleMeterRegistry();
        lenient().when(meterRegistryProvider.getIfAvailable()).thenReturn(meterRegistry);
        service = new PaymentService(paymentRepository, walletService, idempotencyRepository,
                paymentScopeIdempotencyRepository, outboxClient, paymentGateway,
                currencyResolver, transactionManager, meterRegistryProvider);

        Payment payment = new Payment();
        payment.setId(1L);
        payment.setOrderId(10L);
        payment.setCustomerId(2L);
        payment.setAmount(new BigDecimal("100.00"));
        payment.setProviderRef(PROVIDER_REF);
        lenient().when(paymentRepository.findByProviderRef(PROVIDER_REF)).thenReturn(Optional.of(payment));
        lenient().when(paymentRepository.findByProviderRef(GATEWAY_ORDER)).thenReturn(Optional.empty());
        lenient().when(paymentRepository.save(any(Payment.class))).thenAnswer(inv -> inv.getArgument(0));
    }

    private void statusIs(String status) {
        paymentRepository.findByProviderRef(PROVIDER_REF).ifPresent(p -> p.setStatus(status));
    }

    @Test
    void legalTransitions_applyConditionallyAndEmitSettled() {
        record Case(String from, String to) {}
        Set<Case> legal = Set.of(
                new Case(Payment.STATUS_PENDING, Payment.STATUS_SETTLED),
                new Case(Payment.STATUS_PROCESSING, Payment.STATUS_SETTLED),
                new Case(Payment.STATUS_PENDING_WALLET, Payment.STATUS_SETTLED),
                new Case(Payment.STATUS_SETTLED, Payment.STATUS_REFUNDED));

        for (Case c : legal) {
            double before = meterRegistry.counter("payment.webhook.illegal.transitions").count();
            statusIs(c.from());
            when(paymentRepository.transitionStatus(1L, c.from(), c.to())).thenReturn(1);

            Payment payment = service.completeWebhookPayment(GATEWAY_ORDER, PROVIDER_REF, c.to());

            assertThat(payment).isNotNull();
            assertThat(payment.getStatus()).isEqualTo(c.to());
            verify(paymentRepository).transitionStatus(1L, c.from(), c.to());
            // Each legal transition enqueues exactly the settled event once;
            // reset the invocation ledger so verify(...) means THIS case.
            verify(outboxClient).enqueue(any(PlatformEventMessage.class), eq(1L));
            org.mockito.Mockito.clearInvocations(paymentRepository, outboxClient);
            assertThat(meterRegistry.counter("payment.webhook.illegal.transitions").count())
                    .as("no illegal metric for %s → %s", c.from(), c.to())
                    .isEqualTo(before);
        }
    }

    @Test
    void illegalTransitions_areNoOpsWithMetricAndNoEvent() {
        record Case(String from, String to) {}
        Set<Case> illegal = Set.of(
                new Case(Payment.STATUS_SETTLED, Payment.STATUS_SETTLED),     // duplicate captured
                new Case(Payment.STATUS_REFUNDED, Payment.STATUS_SETTLED),    // refund then captured
                new Case(Payment.STATUS_REFUNDED, Payment.STATUS_REFUNDED),   // duplicate refund
                new Case(Payment.STATUS_PENDING, Payment.STATUS_REFUNDED),    // refund before settle
                new Case(Payment.STATUS_FAILED, Payment.STATUS_SETTLED),      // resurrect failed
                new Case(Payment.STATUS_FAILED, Payment.STATUS_REFUNDED));

        for (Case c : illegal) {
            statusIs(c.from());
            double before = meterRegistry.counter("payment.webhook.illegal.transitions").count();

            Payment payment = service.completeWebhookPayment(GATEWAY_ORDER, PROVIDER_REF, c.to());

            assertThat(payment).as("%s → %s must be a no-op", c.from(), c.to()).isNull();
            verify(paymentRepository, never()).transitionStatus(anyLong(), anyString(), anyString());
            assertThat(meterRegistry.counter("payment.webhook.illegal.transitions").count())
                    .as("metric for %s → %s", c.from(), c.to())
                    .isEqualTo(before + 1);
        }
    }

    @Test
    void transitionLostRace_conditionalUpdateZeroRows_isNoOpWithMetric() {
        // Legal on the read, lost to a concurrent transition before the UPDATE:
        // 0 rows → no-op + metric + no event (never a double settle).
        statusIs(Payment.STATUS_PENDING);
        when(paymentRepository.transitionStatus(1L, Payment.STATUS_PENDING, Payment.STATUS_SETTLED))
                .thenReturn(0);

        Payment payment = service.completeWebhookPayment(GATEWAY_ORDER, PROVIDER_REF, Payment.STATUS_SETTLED);

        assertThat(payment).isNull();
        verify(outboxClient, never()).enqueue(any(), anyLong());
        assertThat(meterRegistry.counter("payment.webhook.illegal.transitions").count()).isEqualTo(1);
    }

    @Test
    void unknownPayment_throwsNotFound() {
        when(paymentRepository.findByProviderRef(anyString())).thenReturn(Optional.empty());

        org.assertj.core.api.Assertions.assertThatThrownBy(() ->
                        service.completeWebhookPayment(GATEWAY_ORDER, PROVIDER_REF, Payment.STATUS_SETTLED))
                .isInstanceOf(com.bhukkad.common.error.ResourceNotFoundException.class);
    }
}
