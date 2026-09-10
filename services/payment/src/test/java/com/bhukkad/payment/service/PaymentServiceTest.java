package com.bhukkad.payment.service;

import com.bhukkad.common.error.BusinessException;
import com.bhukkad.common.error.DuplicateRequestException;
import com.bhukkad.common.error.PaymentGatewayException;
import com.bhukkad.common.idempotency.IdempotencyRecord;
import com.bhukkad.common.idempotency.IdempotencyRecordRepository;
import com.bhukkad.common.outbox.OutboxClient;
import com.bhukkad.payment.domain.Payment;
import com.bhukkad.payment.domain.PaymentRepository;
import com.bhukkad.payment.gateway.PaymentGateway;
import com.bhukkad.payment.idempotency.PaymentScopeIdempotencyRepository;
import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.SimpleTransactionStatus;
import org.springframework.transaction.support.TransactionCallback;

import java.math.BigDecimal;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit contract for the feature-#1 idempotent charge flow: claim FIRST,
 * PSP charge OUTSIDE the tx, SETTLED + providerRef + payment_settled outbox in
 * ONE transaction, wallet movement positioned per the batch contract (credit
 * for top-up AFTER the settled commit; METHOD_WALLET order debit inside the
 * settle tx), and replay returning the stored payload without re-charging.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class PaymentServiceTest {

    @Mock private PaymentRepository paymentRepository;
    @Mock private WalletService walletService;
    @Mock private IdempotencyRecordRepository idempotencyRepository;
    @Mock private PaymentScopeIdempotencyRepository paymentScopeIdempotencyRepository;
    @Mock private OutboxClient outboxClient;
    @Mock private PaymentGateway paymentGateway;
    @Mock private PaymentService.PaymentPropertiesGateway currencyResolver;
    @Mock private PlatformTransactionManager transactionManager;
    @Mock private ObjectProvider<MeterRegistry> meterRegistryProvider;

    private PaymentService service;

    private static final String KEY = "key-1";

    @BeforeEach
    void setUp() {
        lenient().when(currencyResolver.currency()).thenReturn("INR");
        lenient().when(transactionManager.getTransaction(any(TransactionDefinition.class)))
                .thenReturn(new SimpleTransactionStatus());
        lenient().when(meterRegistryProvider.getIfAvailable()).thenReturn(null);
        service = new PaymentService(paymentRepository, walletService, idempotencyRepository,
                paymentScopeIdempotencyRepository, outboxClient, paymentGateway,
                currencyResolver, transactionManager, meterRegistryProvider);
    }

    private void freshClaim() {
        lenient().when(idempotencyRepository.insertIfAbsent(
                        eq(KEY), eq(PaymentService.SCOPE_PAYMENT_CHARGE), eq(1L), anyString(),
                        isNull(), any())).thenReturn(1);
        lenient().when(paymentScopeIdempotencyRepository.findStatus(
                        PaymentService.SCOPE_PAYMENT_CHARGE, KEY)).thenReturn(Optional.empty());
    }

    private void persistAssignsIds() {
        lenient().when(paymentRepository.saveAndFlush(any(Payment.class))).thenAnswer(inv -> {
            Payment p = inv.getArgument(0);
            if (p.getId() == null) {
                p.setId(1L);
            }
            return p;
        });
        lenient().when(paymentRepository.findByIdWithLock(1L)).thenAnswer(inv -> {
            Payment p = new Payment();
            p.setId(1L);
            p.setOrderId(10L);
            p.setCustomerId(1L);
            p.setAmount(new BigDecimal("100.00"));
            return Optional.of(p);
        });
        lenient().when(paymentRepository.save(any(Payment.class))).thenAnswer(inv -> inv.getArgument(0));
    }

    @Test
    void processPayment_happyPath_chargesThenSettlesInOneTransaction() {
        freshClaim();
        persistAssignsIds();
        when(paymentGateway.authorize(1L, 1L, new BigDecimal("100.00"), "INR"))
                .thenReturn(PaymentGateway.GatewayResult.ok("pay_1", "order_1"));

        Payment payment = service.processPayment(10L, 1L, new BigDecimal("100.00"), "UPI", KEY);

        assertThat(payment.getStatus()).isEqualTo(Payment.STATUS_SETTLED);
        assertThat(payment.getProviderRef()).isEqualTo("pay_1");
        // Order matters: claim → PSP charge → settle tx → outbox event.
        InOrder order = inOrder(idempotencyRepository, paymentGateway, paymentRepository, outboxClient);
        order.verify(idempotencyRepository).insertIfAbsent(
                eq(KEY), eq(PaymentService.SCOPE_PAYMENT_CHARGE), eq(1L), anyString(), isNull(), any());
        order.verify(paymentGateway).authorize(1L, 1L, new BigDecimal("100.00"), "INR");
        order.verify(paymentRepository).findByIdWithLock(1L);
        order.verify(outboxClient).enqueue(any(com.bhukkad.common.event.PlatformEventMessage.class), eq(1L));
        // Non-wallet order payment must never move the wallet.
        verify(walletService, never()).debit(anyLong(), any(), anyString());
        verify(walletService, never()).credit(anyLong(), any(), anyString());
    }

    @Test
    void processPayment_topUp_creditsWalletOnlyAfterSettleTransactionCommits() {
        freshClaim();
        persistAssignsIds();
        when(paymentGateway.authorize(1L, 1L, new BigDecimal("100.00"), "INR"))
                .thenReturn(PaymentGateway.GatewayResult.ok("pay_1", "order_1"));

        service.processPayment(0L, 1L, new BigDecimal("100.00"), "UPI", KEY);

        InOrder order = inOrder(outboxClient, walletService);
        // The payment_settled outbox enqueue (inside the settle tx) precedes
        // the post-commit wallet credit.
        order.verify(outboxClient).enqueue(any(com.bhukkad.common.event.PlatformEventMessage.class), eq(1L));
        order.verify(walletService).credit(eq(1L), eq(new BigDecimal("100.00")), eq("TOPUP-1"));
    }

    @Test
    void processPayment_walletMethodOrder_debitsInsideSettle() {
        freshClaim();
        persistAssignsIds();
        when(paymentGateway.authorize(1L, 1L, new BigDecimal("40.00"), "INR"))
                .thenReturn(PaymentGateway.GatewayResult.ok("pay_1", "order_1"));

        service.processPayment(10L, 1L, new BigDecimal("40.00"), Payment.METHOD_WALLET, KEY);

        verify(walletService).debit(1L, new BigDecimal("40.00"), "PAYMENT-1");
        verify(walletService, never()).credit(anyLong(), any(), anyString());
    }

    @Test
    void processPayment_duplicateKey_returnsStoredPaymentWithoutRecharge() {
        when(idempotencyRepository.insertIfAbsent(
                eq(KEY), eq(PaymentService.SCOPE_PAYMENT_CHARGE), eq(1L), anyString(),
                isNull(), any())).thenReturn(0);
        when(paymentScopeIdempotencyRepository.findStatus(PaymentService.SCOPE_PAYMENT_CHARGE, KEY))
                .thenReturn(Optional.of("COMPLETED"));
        when(paymentScopeIdempotencyRepository.findResponsePayload(PaymentService.SCOPE_PAYMENT_CHARGE, KEY))
                .thenReturn(Optional.of("{\"paymentId\":5,\"walletMovement\":true}"));
        Payment stored = new Payment();
        stored.setId(5L);
        stored.setOrderId(10L);
        when(paymentRepository.findById(5L)).thenReturn(Optional.of(stored));

        Payment payment = service.processPayment(10L, 1L, new BigDecimal("100.00"), "UPI", KEY);

        assertThat(payment.getId()).isEqualTo(5L);
        verify(paymentGateway, never()).authorize(anyLong(), anyLong(), any(), anyString());
        verify(paymentRepository, never()).saveAndFlush(any(Payment.class));
        verify(outboxClient, never()).enqueue(any(), anyLong());
        verify(walletService, never()).credit(anyLong(), any(), anyString());
    }

    @Test
    void processPayment_inFlightDuplicate_throwsConflict() {
        when(idempotencyRepository.insertIfAbsent(
                eq(KEY), eq(PaymentService.SCOPE_PAYMENT_CHARGE), eq(1L), anyString(),
                isNull(), any())).thenReturn(0);
        when(paymentScopeIdempotencyRepository.findStatus(PaymentService.SCOPE_PAYMENT_CHARGE, KEY))
                .thenReturn(Optional.of("IN_PROGRESS"));
        when(paymentScopeIdempotencyRepository.findResponsePayload(PaymentService.SCOPE_PAYMENT_CHARGE, KEY))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.processPayment(10L, 1L, new BigDecimal("100.00"), "UPI", KEY))
                .isInstanceOf(DuplicateRequestException.class);
        verify(paymentGateway, never()).authorize(anyLong(), anyLong(), any(), anyString());
    }

    @Test
    void processPayment_legacyProcessScopeReplay_returnsStoredPayment() {
        when(idempotencyRepository.findByScopeAndIdempotencyKey(
                IdempotencyRecord.IdempotencyScope.PAYMENT_PROCESS, KEY))
                .thenReturn(Optional.of(completedLegacy()));
        Payment stored = new Payment();
        stored.setId(7L);
        stored.setOrderId(10L);
        when(paymentRepository.findById(7L)).thenReturn(Optional.of(stored));

        Payment payment = service.processPayment(10L, 1L, new BigDecimal("100.00"), "UPI", KEY);

        assertThat(payment.getId()).isEqualTo(7L);
        verify(paymentGateway, never()).authorize(anyLong(), anyLong(), any(), anyString());
    }

    private IdempotencyRecord completedLegacy() {
        IdempotencyRecord record = new IdempotencyRecord();
        record.setIdempotencyKey(KEY);
        record.setScope(IdempotencyRecord.IdempotencyScope.PAYMENT_PROCESS);
        record.setStatus(IdempotencyRecord.IdempotencyStatus.COMPLETED);
        record.setResponsePayload("{\"paymentId\":7}");
        return record;
    }

    @Test
    void processPayment_gatewayDecline_marksFailedAndThrows() {
        freshClaim();
        persistAssignsIds();
        when(paymentGateway.authorize(1L, 1L, new BigDecimal("100.00"), "INR"))
                .thenReturn(PaymentGateway.GatewayResult.failed("declined by PSP"));

        assertThatThrownBy(() -> service.processPayment(10L, 1L, new BigDecimal("100.00"), "UPI", KEY))
                .isInstanceOf(PaymentGatewayException.class)
                .hasMessageContaining("declined");

        verify(paymentRepository).markFailedIfPending(1L);
        verify(paymentScopeIdempotencyRepository).transition(
                eq(PaymentService.SCOPE_PAYMENT_CHARGE), eq(KEY),
                eq(IdempotencyRecord.IdempotencyStatus.FAILED.name()), anyString(), any());
        verify(outboxClient, never()).enqueue(any(), anyLong());
    }

    @Test
    void processPaymentRequested_gatewayDecline_emitsPaymentFailedInFailureTransaction() {
        freshClaim();
        persistAssignsIds();
        when(paymentGateway.authorize(1L, 1L, new BigDecimal("200.00"), "INR"))
                .thenReturn(PaymentGateway.GatewayResult.failed("declined by PSP"));

        assertThatThrownBy(() -> service.processPaymentRequested(10L, 1L,
                new BigDecimal("200.00"), "INR", KEY))
                .isInstanceOf(PaymentGatewayException.class);

        ArgumentCaptor<com.bhukkad.common.event.PlatformEventMessage> event =
                ArgumentCaptor.forClass(com.bhukkad.common.event.PlatformEventMessage.class);
        verify(outboxClient).enqueue(event.capture(), eq(10L));
        assertThat(event.getValue().eventType()).isEqualTo(PaymentService.EVENT_PAYMENT_FAILED);
        assertThat(event.getValue().payload()).contains("\"orderId\":10");
        verify(paymentRepository).markFailedIfPending(1L);
    }

    @Test
    void processPaymentRequested_unsupportedCurrency_failsWithoutCharging() {
        assertThatThrownBy(() -> service.processPaymentRequested(10L, 1L,
                new BigDecimal("100.00"), "USD", KEY))
                .isInstanceOf(PaymentGatewayException.class)
                .hasMessageContaining("Unsupported currency");

        verify(paymentGateway, never()).authorize(anyLong(), anyLong(), any(), anyString());
    }

    @Test
    void processPayment_negativeAmount_throws() {
        assertThatThrownBy(() -> service.processPayment(10L, 1L, new BigDecimal("-40.00"), "UPI", KEY))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("positive");
        verify(paymentGateway, never()).authorize(anyLong(), anyLong(), any(), anyString());
    }

    @Test
    void processPayment_blankIdempotencyKey_throws() {
        assertThatThrownBy(() -> service.processPayment(10L, 1L, new BigDecimal("40.00"), "UPI", " "))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("Idempotency key");
    }

    @Test
    void getPayment_found_returnsPayment() {
        Payment payment = new Payment();
        payment.setId(1L);
        when(paymentRepository.findById(1L)).thenReturn(Optional.of(payment));

        assertThat(service.getPayment(1L).getId()).isEqualTo(1L);
    }

    @Test
    void getPayment_notFound_throws() {
        when(paymentRepository.findById(99L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.getPayment(99L))
                .isInstanceOf(com.bhukkad.common.error.ResourceNotFoundException.class)
                .hasMessageContaining("not found");
    }
}
