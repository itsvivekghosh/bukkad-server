package com.bhukkad.serviceImpl;

import com.bhukkad.entity.Customer;
import com.bhukkad.entity.Order;
import com.bhukkad.entity.Payment;
import com.bhukkad.exception.ResourceNotFoundException;
import com.bhukkad.idempotency.PaymentIdempotencyService;
import com.bhukkad.payment.PaymentGateway;
import com.bhukkad.payment.PaymentProperties;
import com.bhukkad.payment.strategy.PaymentStrategyFactory;
import com.bhukkad.payment.strategy.PaymentStrategy;
import com.bhukkad.repository.CustomerRepository;
import com.bhukkad.repository.OrderRepository;
import com.bhukkad.repository.PaymentRepository;
import com.bhukkad.security.SecurityUtils;
import com.bhukkad.service.NotificationService;
import com.bhukkad.timeline.OrderTimelineService;
import com.bhukkad.wallet.WalletService;
import com.bhukkad.wallet.WalletTopUpService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PaymentServiceImplTest {

    @Mock
    private PaymentRepository paymentRepository;
    @Mock
    private OrderRepository orderRepository;
    @Mock
    private CustomerRepository customerRepository;
    @Mock
    private PaymentGateway paymentGateway;
    @Mock
    private PaymentProperties paymentProperties;
    @Mock
    private PaymentIdempotencyService paymentIdempotencyService;
    @Mock
    private SecurityUtils securityUtils;
    @Mock
    private NotificationService notificationService;
    @Mock
    private WalletService walletService;
    @Mock
    private WalletTopUpService walletTopUpService;
    @Mock
    private PaymentStrategyFactory paymentStrategyFactory;
    @Mock
    private PaymentStrategy paymentStrategy;
    @Mock
    private OrderTimelineService orderTimelineService;

    @InjectMocks
    private PaymentServiceImpl paymentService;

    @BeforeEach
    void setUp() {
        PaymentProperties.Razorpay razorpay = new PaymentProperties.Razorpay();
        razorpay.setEnabled(false);
        lenient().when(paymentProperties.getRazorpay()).thenReturn(razorpay);
    }

    private Order order() {
        Customer customer = new Customer();
        customer.setId(2L);
        customer.setWalletBalance(500.0);

        Order order = new Order();
        order.setId(5L);
        order.setOrderNumber("ORD-TEST");
        order.setTotalAmount(250.0);
        order.setCustomer(customer);
        return order;
    }

    private Payment payment(Payment.PaymentStatus status) {
        Payment payment = new Payment();
        payment.setId(1L);
        payment.setOrder(order());
        payment.setAmount(250.0);
        payment.setGatewayAmount(250.0);
        payment.setWalletAmount(0.0);
        payment.setStatus(status);
        payment.setPaymentMethod(Payment.PaymentMethod.UPI);
        return payment;
    }

    @Test
    void createPayment_orderNotFound_throwsResourceNotFound() {
        when(orderRepository.findById(99L)).thenReturn(Optional.empty());

        ResourceNotFoundException ex = assertThrows(ResourceNotFoundException.class,
                () -> paymentService.createPayment(99L, "UPI", null));
        assertEquals("Order not found", ex.getMessage());
    }

    @Test
    void createPayment_invalidMethod_throwsIllegalArgumentException() {
        when(orderRepository.findById(5L)).thenReturn(Optional.of(order()));

        assertThrows(IllegalArgumentException.class,
                () -> paymentService.createPayment(5L, "INVALID_METHOD", null));
        verify(paymentRepository, never()).save(any());
    }

    @Test
    void createPayment_success() {
        when(orderRepository.findById(5L)).thenReturn(Optional.of(order()));
        when(paymentRepository.save(any(Payment.class))).thenAnswer(inv -> {
            Payment p = inv.getArgument(0);
            p.setId(1L);
            return p;
        });

        Payment result = paymentService.createPayment(5L, "UPI", null);

        assertEquals(1L, result.getId());
        assertEquals(Payment.PaymentMethod.UPI, result.getPaymentMethod());
        assertEquals(250.0, result.getAmount());
        assertEquals(Payment.PaymentStatus.PENDING, result.getStatus());
    }

    @Test
    void processPayment_notFound_throwsResourceNotFound() {
        when(paymentRepository.findById(99L)).thenReturn(Optional.empty());

        assertThrows(ResourceNotFoundException.class, () -> paymentService.processPayment(99L, null));
    }

    @Test
    void processPayment_alreadyCompleted_returnsExisting() {
        Payment completed = payment(Payment.PaymentStatus.COMPLETED);
        when(paymentRepository.findById(1L)).thenReturn(Optional.of(completed));

        Payment result = paymentService.processPayment(1L, null);

        assertEquals(Payment.PaymentStatus.COMPLETED, result.getStatus());
        verify(paymentRepository, never()).save(any());
    }

    @Test
    void processPayment_pending_succeeds() {
        Payment pending = payment(Payment.PaymentStatus.PENDING);
        pending.setGatewayAmount(250.0);
        when(paymentRepository.findById(1L)).thenReturn(Optional.of(pending));
        when(paymentRepository.save(any(Payment.class))).thenAnswer(inv -> inv.getArgument(0));
        when(paymentStrategyFactory.getStrategy(Payment.PaymentMethod.UPI)).thenReturn(paymentStrategy);

        Payment completed = payment(Payment.PaymentStatus.COMPLETED);
        completed.setTransactionId("TXN-1");
        completed.setCompletedAt(LocalDateTime.of(2024, 1, 1, 0, 0));
        when(paymentStrategy.process(any(com.bhukkad.payment.strategy.PaymentContext.class))).thenReturn(completed);

        Payment result = paymentService.processPayment(1L, null);

        assertEquals(Payment.PaymentStatus.COMPLETED, result.getStatus());
        assertNotNull(result.getCompletedAt());
        assertEquals("TXN-1", result.getTransactionId());
    }

    @Test
    void processPayment_wallet_setsWalletTransaction() {
        Payment pending = payment(Payment.PaymentStatus.PENDING);
        pending.setPaymentMethod(Payment.PaymentMethod.WALLET);
        when(paymentRepository.findById(1L)).thenReturn(Optional.of(pending));
        when(paymentRepository.save(pending)).thenReturn(pending);

        Payment result = paymentService.processPayment(1L, null);

        assertEquals(Payment.PaymentStatus.COMPLETED, result.getStatus());
        assertTrue(result.getTransactionId().startsWith("WALLET-"));
    }

    @Test
    void getPaymentByOrderId_missing_returnsNull() {
        when(paymentRepository.findByOrderId(5L)).thenReturn(Optional.empty());
        assertNull(paymentService.getPaymentByOrderId(5L));
    }

    @Test
    void refundPayment_completed_succeeds() {
        Payment completed = payment(Payment.PaymentStatus.COMPLETED);
        when(paymentRepository.findById(1L)).thenReturn(Optional.of(completed));
        when(paymentRepository.save(completed)).thenReturn(completed);

        paymentService.refundPayment(1L);

        assertEquals(Payment.PaymentStatus.REFUNDED, completed.getStatus());
    }

    @Test
    void refundPayment_recordsRefundTimelineEvent() {
        Payment completed = payment(Payment.PaymentStatus.COMPLETED);
        when(paymentRepository.findById(1L)).thenReturn(Optional.of(completed));
        when(paymentRepository.save(completed)).thenReturn(completed);

        paymentService.refundPayment(1L);

        verify(orderTimelineService).recordEvent(
                eq(5L), eq("ORDER_REFUNDED"), any(), any(), any(), any());
    }

    @Test
    void refundPayment_timelineFailureDoesNotFailRefund() {
        Payment completed = payment(Payment.PaymentStatus.COMPLETED);
        when(paymentRepository.findById(1L)).thenReturn(Optional.of(completed));
        when(paymentRepository.save(completed)).thenReturn(completed);
        when(orderTimelineService.recordEvent(any(), any(), any(), any(), any(), any()))
                .thenThrow(new RuntimeException("timeline down"));

        paymentService.refundPayment(1L);

        assertEquals(Payment.PaymentStatus.REFUNDED, completed.getStatus());
    }

    @Test
    void refundPayment_alreadyRefunded_isIdempotent() {
        Payment refunded = payment(Payment.PaymentStatus.REFUNDED);
        when(paymentRepository.findById(1L)).thenReturn(Optional.of(refunded));

        paymentService.refundPayment(1L);

        verify(paymentRepository, never()).save(any());
    }
}
