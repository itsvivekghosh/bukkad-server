package com.bhukkad.payment;

import com.bhukkad.entity.Customer;
import com.bhukkad.entity.Order;
import com.bhukkad.entity.Payment;
import com.bhukkad.entity.WalletTransaction;
import com.bhukkad.repository.OrderRepository;
import com.bhukkad.repository.PaymentRepository;
import com.bhukkad.service.PaymentService;
import com.bhukkad.wallet.WalletService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.LocalDateTime;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link AutoRefundService}, the cancellation-policy refund
 * orchestrator.
 */
@ExtendWith(MockitoExtension.class)
class AutoRefundServiceTest {

    private static final Long ORDER_ID = 42L;
    private static final String REASON = "CUSTOMER_CANCELLED";

    @Mock
    private RefundPolicyService refundPolicyService;

    @Mock
    private PaymentService paymentService;

    @Mock
    private WalletService walletService;

    @Mock
    private OrderRepository orderRepository;

    @Mock
    private PaymentRepository paymentRepository;

    @Mock
    private StringRedisTemplate stringRedisTemplate;

    @Mock
    private ValueOperations<String, String> valueOps;

    private AutoRefundService service;

    @BeforeEach
    void setUp() {
        org.mockito.Mockito.lenient().when(stringRedisTemplate.opsForValue()).thenReturn(valueOps);
        service = new AutoRefundService(
                refundPolicyService, paymentService, walletService, orderRepository, paymentRepository,
                stringRedisTemplate);
    }

    private Order order() {
        Customer customer = new Customer();
        customer.setId(1L);
        Order order = new Order();
        order.setId(ORDER_ID);
        order.setOrderNumber("ORD-42");
        order.setCustomer(customer);
        order.setTotalAmount(500.0);
        order.setCreatedAt(LocalDateTime.now().minusMinutes(10));
        return order;
    }

    private Payment pendingPayment() {
        Payment payment = new Payment();
        payment.setId(7L);
        payment.setAmount(500.0);
        payment.setStatus(Payment.PaymentStatus.PENDING);
        return payment;
    }

    private void stubGatewayPolicy() {
        when(refundPolicyService.computeRefund(any(Order.class), anyString()))
                .thenReturn(Optional.of(new RefundPolicyService.RefundPolicy(
                        100.0, RefundPolicyService.TARGET_GATEWAY, 30)));
    }

    /** Simulates the Redis SETNX claim: returns true once, then false until released. */
    private void stubRedisClaim() {
        AtomicBoolean claimed = new AtomicBoolean(false);
        org.mockito.Mockito.lenient().when(valueOps.setIfAbsent(anyString(), anyString(), any()))
                .thenAnswer(inv -> claimed.compareAndSet(false, true));
        org.mockito.Mockito.lenient().doAnswer(inv -> {
            claimed.set(false);
            return true;
        }).when(stringRedisTemplate).delete(anyString());
    }

    @Test
    void autoRefund_gatewayTarget_callsProcessRefundWithFullAmount() {
        stubGatewayPolicy();
        stubRedisClaim();
        when(paymentRepository.findByOrderId(ORDER_ID)).thenReturn(Optional.of(pendingPayment()));

        boolean refunded = service.autoRefund(order(), REASON);

        assertTrue(refunded);
        verify(paymentService).processRefund(eq(ORDER_ID), eq(500.0), eq(REASON));
        verify(walletService, never()).credit(any(), anyDouble(), any(), any(), anyString());
    }

    @Test
    void autoRefund_walletTarget_creditsWalletInsteadOfGateway() {
        when(refundPolicyService.computeRefund(any(Order.class), anyString()))
                .thenReturn(Optional.of(new RefundPolicyService.RefundPolicy(
                        100.0, RefundPolicyService.TARGET_WALLET, 30)));
        stubRedisClaim();
        when(paymentRepository.findByOrderId(ORDER_ID)).thenReturn(Optional.of(pendingPayment()));

        Order order = order();
        boolean refunded = service.autoRefund(order, REASON);

        assertTrue(refunded);
        verify(walletService).credit(eq(1L), eq(500.0),
                eq(WalletTransaction.TransactionType.ORDER_REFUND), any(), anyString());
        verify(paymentService, never()).processRefund(any(), anyDouble(), anyString());
    }

    @Test
    void autoRefund_noPolicy_noRefundAttempted() {
        when(refundPolicyService.computeRefund(any(Order.class), anyString()))
                .thenReturn(Optional.empty());

        boolean refunded = service.autoRefund(order(), REASON);

        assertFalse(refunded);
        verify(paymentService, never()).processRefund(any(), anyDouble(), anyString());
        verify(walletService, never()).credit(any(), anyDouble(), any(), any(), anyString());
    }

    @Test
    void autoRefund_paymentAlreadyRefunded_noSecondRefund() {
        stubGatewayPolicy();
        stubRedisClaim();
        Payment payment = pendingPayment();
        payment.setStatus(Payment.PaymentStatus.REFUNDED);
        when(paymentRepository.findByOrderId(ORDER_ID)).thenReturn(Optional.of(payment));

        boolean refunded = service.autoRefund(order(), REASON);

        assertFalse(refunded);
        verify(paymentService, never()).processRefund(any(), anyDouble(), anyString());
    }

    @Test
    void autoRefund_doubleCall_preventedByIdempotency() {
        stubGatewayPolicy();
        stubRedisClaim();
        when(paymentRepository.findByOrderId(ORDER_ID)).thenReturn(Optional.of(pendingPayment()));

        boolean first = service.autoRefund(order(), REASON);
        boolean second = service.autoRefund(order(), REASON);

        assertTrue(first);
        assertFalse(second);
        verify(paymentService, times(1)).processRefund(eq(ORDER_ID), eq(500.0), eq(REASON));
        verify(walletService, never()).credit(any(), anyDouble(), any(), any(), anyString());
    }

    @Test
    void autoRefund_secondReplica_doesNotDoubleRefund() {
        stubGatewayPolicy();
        stubRedisClaim();
        when(paymentRepository.findByOrderId(ORDER_ID)).thenReturn(Optional.of(pendingPayment()));

        AutoRefundService replicaB = new AutoRefundService(
                refundPolicyService, paymentService, walletService, orderRepository, paymentRepository,
                stringRedisTemplate);

        assertTrue(service.autoRefund(order(), REASON));
        assertFalse(replicaB.autoRefund(order(), REASON));
        verify(paymentService, times(1)).processRefund(eq(ORDER_ID), eq(500.0), eq(REASON));
    }

    @Test
    void autoRefund_exceptionInGateway_isSwallowedAndReportedAsNotRefunded() {
        stubGatewayPolicy();
        stubRedisClaim();
        when(paymentRepository.findByOrderId(ORDER_ID)).thenReturn(Optional.of(pendingPayment()));
        org.mockito.Mockito.doThrow(new IllegalStateException("gateway down"))
                .when(paymentService).processRefund(eq(ORDER_ID), eq(500.0), eq(REASON));

        boolean refunded = service.autoRefund(order(), REASON);

        assertFalse(refunded);
    }

    @Test
    void autoRefund_failureDoesNotBlockLaterRetry() {
        stubGatewayPolicy();
        stubRedisClaim();
        when(paymentRepository.findByOrderId(ORDER_ID)).thenReturn(Optional.of(pendingPayment()));
        org.mockito.Mockito.doThrow(new IllegalStateException("transient"))
                .when(paymentService).processRefund(eq(ORDER_ID), eq(500.0), eq(REASON));

        assertFalse(service.autoRefund(order(), REASON));
        assertFalse(service.autoRefund(order(), REASON));
        verify(paymentService, times(2)).processRefund(eq(ORDER_ID), eq(500.0), eq(REASON));
    }

    @Test
    void autoRefund_redisUnavailable_fallsBackToLocalClaim() {
        stubGatewayPolicy();
        when(valueOps.setIfAbsent(anyString(), anyString(), any()))
                .thenThrow(new IllegalStateException("redis down"));
        when(paymentRepository.findByOrderId(ORDER_ID)).thenReturn(Optional.of(pendingPayment()));

        boolean refunded = service.autoRefund(order(), REASON);

        assertTrue(refunded);
        verify(paymentService).processRefund(eq(ORDER_ID), eq(500.0), eq(REASON));
    }

    @Test
    void autoRefund_nullOrder_returnsFalseWithoutNpe() {
        assertFalse(service.autoRefund((Order) null, REASON));
    }
}