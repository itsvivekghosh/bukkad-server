package com.bhukkad.payment;

import com.bhukkad.entity.Customer;
import com.bhukkad.entity.Order;
import com.bhukkad.entity.Payment;
import com.bhukkad.entity.WalletTransaction;
import com.bhukkad.repository.OrderRepository;
import com.bhukkad.repository.PaymentRepository;
import com.bhukkad.serviceImpl.PaymentServiceImpl;
import com.bhukkad.wallet.WalletService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.Optional;

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
    private PaymentServiceImpl paymentServiceImpl;

    @Mock
    private WalletService walletService;

    @Mock
    private OrderRepository orderRepository;

    @Mock
    private PaymentRepository paymentRepository;

    private AutoRefundService service;

    @BeforeEach
    void setUp() {
        service = new AutoRefundService(
                refundPolicyService, paymentServiceImpl, walletService, orderRepository, paymentRepository);
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

    @Test
    void autoRefund_gatewayTarget_callsProcessRefundWithFullAmount() {
        stubGatewayPolicy();
        when(paymentRepository.findByOrderId(ORDER_ID)).thenReturn(Optional.of(pendingPayment()));

        boolean refunded = service.autoRefund(order(), REASON);

        assertTrue(refunded);
        verify(paymentServiceImpl).processRefund(eq(ORDER_ID), eq(500.0), eq(REASON));
        verify(walletService, never()).credit(any(), anyDouble(), any(), any(), anyString());
    }

    @Test
    void autoRefund_walletTarget_creditsWalletInsteadOfGateway() {
        when(refundPolicyService.computeRefund(any(Order.class), anyString()))
                .thenReturn(Optional.of(new RefundPolicyService.RefundPolicy(
                        100.0, RefundPolicyService.TARGET_WALLET, 30)));
        when(paymentRepository.findByOrderId(ORDER_ID)).thenReturn(Optional.of(pendingPayment()));

        Order order = order();
        boolean refunded = service.autoRefund(order, REASON);

        assertTrue(refunded);
        verify(walletService).credit(eq(order.getCustomer()), eq(500.0),
                eq(WalletTransaction.TransactionType.ORDER_REFUND), any(Payment.class), anyString());
        verify(paymentServiceImpl, never()).processRefund(any(), anyDouble(), anyString());
    }

    @Test
    void autoRefund_noPolicy_noRefundAttempted() {
        when(refundPolicyService.computeRefund(any(Order.class), anyString()))
                .thenReturn(Optional.empty());

        boolean refunded = service.autoRefund(order(), REASON);

        assertFalse(refunded);
        verify(paymentServiceImpl, never()).processRefund(any(), anyDouble(), anyString());
        verify(walletService, never()).credit(any(), anyDouble(), any(), any(), anyString());
    }

    @Test
    void autoRefund_paymentAlreadyRefunded_noSecondRefund() {
        stubGatewayPolicy();
        Payment payment = pendingPayment();
        payment.setStatus(Payment.PaymentStatus.REFUNDED);
        when(paymentRepository.findByOrderId(ORDER_ID)).thenReturn(Optional.of(payment));

        boolean refunded = service.autoRefund(order(), REASON);

        assertFalse(refunded);
        verify(paymentServiceImpl, never()).processRefund(any(), anyDouble(), anyString());
    }

    @Test
    void autoRefund_doubleCall_preventedByIdempotency() {
        stubGatewayPolicy();
        when(paymentRepository.findByOrderId(ORDER_ID)).thenReturn(Optional.of(pendingPayment()));

        boolean first = service.autoRefund(order(), REASON);
        boolean second = service.autoRefund(order(), REASON);

        assertTrue(first);
        assertFalse(second);
        // The refund path must run exactly once for the same order + reason.
        verify(paymentServiceImpl, times(1)).processRefund(eq(ORDER_ID), eq(500.0), eq(REASON));
        verify(walletService, never()).credit(any(), anyDouble(), any(), any(), anyString());
    }

    @Test
    void autoRefund_exceptionInGateway_isSwallowedAndReportedAsNotRefunded() {
        stubGatewayPolicy();
        when(paymentRepository.findByOrderId(ORDER_ID)).thenReturn(Optional.of(pendingPayment()));
        org.mockito.Mockito.doThrow(new IllegalStateException("gateway down"))
                .when(paymentServiceImpl).processRefund(eq(ORDER_ID), eq(500.0), eq(REASON));

        boolean refunded = service.autoRefund(order(), REASON);

        assertFalse(refunded);
    }

    @Test
    void autoRefund_failureDoesNotBlockLaterRetry() {
        stubGatewayPolicy();
        when(paymentRepository.findByOrderId(ORDER_ID)).thenReturn(Optional.of(pendingPayment()));
        org.mockito.Mockito.doThrow(new IllegalStateException("transient"))
                .when(paymentServiceImpl).processRefund(eq(ORDER_ID), eq(500.0), eq(REASON));

        assertFalse(service.autoRefund(order(), REASON));
        // After the failed attempt the marker is released, so a later call retries.
        assertFalse(service.autoRefund(order(), REASON));
        verify(paymentServiceImpl, times(2)).processRefund(eq(ORDER_ID), eq(500.0), eq(REASON));
    }

    @Test
    void autoRefund_nullOrder_returnsFalseWithoutNpe() {
        assertFalse(service.autoRefund((Order) null, REASON));
    }
}
