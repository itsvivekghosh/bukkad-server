package com.bhukkad.serviceImpl;

import com.bhukkad.dto.response.PaymentResponse;
import com.bhukkad.entity.Customer;
import com.bhukkad.entity.Order;
import com.bhukkad.entity.Payment;
import com.bhukkad.entity.WalletTransaction;
import com.bhukkad.exception.BusinessException;
import com.bhukkad.exception.ResourceNotFoundException;
import com.bhukkad.exception.UnauthorizedException;
import com.bhukkad.idempotency.PaymentIdempotencyService;
import com.bhukkad.payment.DunningService;
import com.bhukkad.payment.PaymentGateway;
import com.bhukkad.payment.PaymentProperties;
import com.bhukkad.payment.strategy.PaymentContext;
import com.bhukkad.payment.strategy.BNPLStrategy;
import com.bhukkad.payment.strategy.PaymentStrategyFactory;
import com.bhukkad.repository.OrderRepository;
import com.bhukkad.repository.PaymentRepository;
import com.bhukkad.security.SecurityUtils;
import com.bhukkad.service.NotificationService;
import com.bhukkad.timeline.OrderTimelineService;
import com.bhukkad.util.PriceCalculator;
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
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PaymentServiceImplTest {

    @Mock
    private OrderRepository orderRepository;
    @Mock
    private PaymentRepository paymentRepository;
    @Mock
    private PaymentGateway paymentGateway;
    @Mock
    private PaymentProperties paymentProperties;
    @Mock
    private PaymentProperties.Razorpay razorpay;
    @Mock
    private PaymentStrategyFactory paymentStrategyFactory;
    @Mock
    private PaymentIdempotencyService paymentIdempotencyService;
    @Mock
    private DunningService dunningService;
    @Mock
    private SecurityUtils securityUtils;
    @Mock
    private NotificationService notificationService;
    @Mock
    private WalletService walletService;
    @Mock
    private WalletTopUpService walletTopUpService;
    @Mock
    private BNPLStrategy bnplStrategy;
    @Mock
    private OrderTimelineService orderTimelineService;

    @InjectMocks
    private PaymentServiceImpl service;

    private Order order;
    private Payment payment;
    private Customer customer;

    @BeforeEach
    void setUp() {
        customer = new Customer();
        customer.setId(1L);

        order = new Order();
        order.setId(1L);
        order.setOrderNumber("ORD-123");
        order.setTotalAmount(100.0);
        order.setWalletAmountUsed(10.0);
        order.setCustomer(customer);

        payment = new Payment();
        payment.setId(1L);
        payment.setOrder(order);
        payment.setCustomer(customer);
        payment.setPaymentMethod(Payment.PaymentMethod.CREDIT_CARD);
        payment.setAmount(100.0);
        payment.setStatus(Payment.PaymentStatus.PENDING);
        payment.setIdempotencyKey("idem-key");

        lenient().when(paymentProperties.getRazorpay()).thenReturn(razorpay);
        lenient().when(razorpay.isEnabled()).thenReturn(true);
        lenient().when(razorpay.getCurrency()).thenReturn("INR");
    }

    @Test
    void createPayment_createsPaymentWithIdempotencyKey() {
        when(orderRepository.findById(1L)).thenReturn(Optional.of(order));
        when(paymentRepository.save(any(Payment.class))).thenAnswer(inv -> inv.getArgument(0));

        Payment result = service.createPayment(1L, "CREDIT_CARD", "idem-key");

        assertEquals(Payment.PaymentStatus.PENDING, result.getStatus());
        // Gateway order creation moved out of createPayment: it happens lazily in
        // GatewayPaymentStrategy#process (outside any DB transaction), so the
        // PENDING payment row is created without holding the DB tx across the
        // external gateway call.
        assertNull(result.getGatewayOrderId());
        verify(paymentGateway, never()).createOrder(any());
    }

    @Test
    void createPayment_returnsCached_whenIdempotencyKeyExists() {
        Payment cached = new Payment();
        cached.setId(99L);
        when(paymentRepository.findByIdempotencyKey("idem-key")).thenReturn(Optional.of(cached));

        Payment result = service.createPayment(1L, "CREDIT_CARD", "idem-key");

        assertEquals(99L, result.getId());
    }

    @Test
    void processPayment_returnsCached_whenCompleted() {
        payment.setStatus(Payment.PaymentStatus.COMPLETED);
        when(paymentRepository.findById(1L)).thenReturn(Optional.of(payment));

        Payment result = service.processPayment(1L, "idem-key");

        assertEquals(Payment.PaymentStatus.COMPLETED, result.getStatus());
    }

    @Test
    void processPayment_skipsGateway_forCashOnDelivery() {
        payment.setPaymentMethod(Payment.PaymentMethod.CASH_ON_DELIVERY);
        when(paymentRepository.findById(1L)).thenReturn(Optional.of(payment));
        when(paymentRepository.save(any())).thenReturn(payment);

        Payment result = service.processPayment(1L, "idem-key");

        assertEquals(Payment.PaymentStatus.PENDING, result.getStatus());
    }

    @Test
    void processPayment_usesWallet_whenWalletPayment() {
        payment.setPaymentMethod(Payment.PaymentMethod.WALLET);
        when(paymentRepository.findById(1L)).thenReturn(Optional.of(payment));
        when(paymentRepository.save(any())).thenReturn(payment);

        Payment result = service.processPayment(1L, "idem-key");

        assertEquals(Payment.PaymentStatus.COMPLETED, result.getStatus());
    }

    @Test
    void refundPayment_creditsWallet() {
        Payment payment = new Payment();
        payment.setId(1L);
        payment.setStatus(Payment.PaymentStatus.COMPLETED);
        payment.setWalletAmount(50.0);
        payment.setGatewayAmount(0.0);

        Order order = new Order();
        order.setId(1L);
        order.setOrderNumber("ORD-123");
        Customer customer = new Customer();
        customer.setId(1L);
        order.setCustomer(customer);
        payment.setOrder(order);

        when(paymentRepository.findByIdWithLock(1L)).thenReturn(Optional.of(payment));
        when(paymentRepository.save(any())).thenReturn(payment);

        service.refundPayment(1L);

        assertEquals(Payment.PaymentStatus.REFUNDED, payment.getStatus());
    }

    @Test
    void refundPayment_throws_whenNotCompleted() {
        payment.setStatus(Payment.PaymentStatus.FAILED);
        when(paymentRepository.findByIdWithLock(1L)).thenReturn(Optional.of(payment));

        assertThrows(BusinessException.class,
                () -> service.refundPayment(1L));
    }

    @Test
    void getPaymentForOrder_returnsResponse() {
        order.setCustomer(customer);
        when(orderRepository.findByIdWithDetails(1L)).thenReturn(Optional.of(order));
        when(securityUtils.getCurrentUserId()).thenReturn(1L);
        when(paymentRepository.findByOrderId(1L)).thenReturn(Optional.of(payment));

        PaymentResponse response = service.getPaymentForOrder(1L);

        assertNotNull(response);
        assertEquals(payment.getId(), response.getId());
    }

    @Test
    void completeWebhookPayment_completesWalletTopUp() {
        payment.setPurpose(Payment.PaymentPurpose.WALLET_TOP_UP);
        when(paymentRepository.findByGatewayOrderId("gateway-1")).thenReturn(Optional.of(payment));

        service.completeWebhookPayment("gateway-1", "gateway-payment-1");

        verify(walletTopUpService).completeTopUp(payment, "gateway-payment-1");
    }

    @Test
    void completeWebhookPayment_completesOrderPayment() {
        payment.setPurpose(Payment.PaymentPurpose.ORDER);
        when(paymentRepository.findByGatewayOrderId("gateway-1")).thenReturn(Optional.of(payment));
        when(paymentRepository.save(any())).thenReturn(payment);

        service.completeWebhookPayment("gateway-1", "gateway-payment-1");

        assertEquals(Payment.PaymentStatus.COMPLETED, payment.getStatus());
        assertEquals("gateway-payment-1", payment.getGatewayPaymentId());
    }

    @Test
    void completeWebhookPayment_skips_whenAlreadyCompleted() {
        payment.setStatus(Payment.PaymentStatus.COMPLETED);
        when(paymentRepository.findByGatewayOrderId("gateway-1")).thenReturn(Optional.of(payment));

        service.completeWebhookPayment("gateway-1", "gateway-payment-1");

        // No interactions expected beyond find
    }

    // ==================== createPayment edge paths ====================

    @Test
    void createPayment_orderNotFound_throws() {
        when(orderRepository.findById(1L)).thenReturn(Optional.empty());

        assertThrows(ResourceNotFoundException.class,
                () -> service.createPayment(1L, "CREDIT_CARD", null));
    }

    @Test
    void createPayment_withoutIdempotencyKey_createsNew() {
        order.setWalletAmountUsed(null);
        order.setTotalAmount(150.0);
        when(orderRepository.findById(1L)).thenReturn(Optional.of(order));
        when(paymentRepository.save(any(Payment.class))).thenAnswer(inv -> inv.getArgument(0));

        Payment result = service.createPayment(1L, "UPI", null);

        assertEquals(150.0, result.getAmount());
        assertEquals(0.0, result.getWalletAmount());
        assertNull(result.getGatewayOrderId());
        assertNull(result.getIdempotencyKey());
    }

    @Test
    void createPayment_codSkipsGateway() {
        when(orderRepository.findById(1L)).thenReturn(Optional.of(order));
        when(paymentRepository.save(any(Payment.class))).thenAnswer(inv -> inv.getArgument(0));

        Payment result = service.createPayment(1L, "CASH_ON_DELIVERY", null);

        assertEquals(Payment.PaymentStatus.PENDING, result.getStatus());
        verify(paymentGateway, never()).createOrder(any());
    }

    @Test
    void createPayment_neverCallsGateway() {
        when(orderRepository.findById(1L)).thenReturn(Optional.of(order));
        when(paymentRepository.save(any(Payment.class))).thenAnswer(inv -> inv.getArgument(0));

        service.createPayment(1L, "CREDIT_CARD", null);

        // Gateway order creation is deferred to GatewayPaymentStrategy#process,
        // so createPayment must never hit the gateway.
        verify(paymentGateway, never()).createOrder(any());
    }

    @Test
    void createPayment_gatewayMethodWithZeroGatewayAmount_skipsGateway() {
        order.setWalletAmountUsed(100.0);
        order.setTotalAmount(0.0);
        when(orderRepository.findById(1L)).thenReturn(Optional.of(order));
        when(paymentRepository.save(any(Payment.class))).thenAnswer(inv -> inv.getArgument(0));

        Payment result = service.createPayment(1L, "UPI", null);

        assertEquals(100.0, result.getAmount());
        verify(paymentGateway, never()).createOrder(any());
    }

    // ==================== processPayment edge paths ====================

    @Test
    void processPayment_returnsIdempotentCachedResult() {
        Payment cached = new Payment();
        cached.setId(77L);
        cached.setStatus(Payment.PaymentStatus.COMPLETED);
        when(paymentIdempotencyService.findCompletedPayment("idem-key")).thenReturn(Optional.of(cached));

        Payment result = service.processPayment(1L, "idem-key");

        assertEquals(77L, result.getId());
        verify(paymentRepository, never()).findById(anyLong());
    }

    @Test
    void processPayment_paymentNotFound_throws() {
        when(paymentRepository.findById(404L)).thenReturn(Optional.empty());

        assertThrows(ResourceNotFoundException.class,
                () -> service.processPayment(404L, "idem-key"));
        // Not-found happens before processing starts; no failure marking needed
        verify(paymentIdempotencyService, never()).failPaymentProcess(anyString());
    }

    @Test
    void processPayment_nullOrZeroGatewayAmount_completesAsWallet() {
        payment.setPaymentMethod(Payment.PaymentMethod.UPI);
        payment.setGatewayAmount(null);
        when(paymentRepository.findById(1L)).thenReturn(Optional.of(payment));
        when(paymentRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        Payment result = service.processPayment(1L, "idem-key");

        assertEquals(Payment.PaymentStatus.COMPLETED, result.getStatus());
        assertTrue(result.getTransactionId().startsWith("WALLET-"));
        verify(paymentIdempotencyService).completePaymentProcess(eq("idem-key"), any(Payment.class));
    }

    @Test
    void processPayment_gatewayStrategy_success() {
        payment.setGatewayAmount(90.0);
        var strategy = mock(com.bhukkad.payment.strategy.PaymentStrategy.class);
        when(paymentRepository.findById(1L)).thenReturn(Optional.of(payment));
        when(paymentStrategyFactory.getStrategy(Payment.PaymentMethod.CREDIT_CARD)).thenReturn(strategy);
        when(strategy.process(any(PaymentContext.class))).thenReturn(payment);
        when(paymentRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        Payment result = service.processPayment(1L, "idem-key");

        assertEquals(payment, result);
        verify(paymentIdempotencyService).completePaymentProcess("idem-key", payment);
    }

    @Test
    void processPayment_gatewayStrategy_failure_throwsAndMarksFailed() {
        payment.setGatewayAmount(90.0);
        payment.setStatus(Payment.PaymentStatus.FAILED);
        var strategy = mock(com.bhukkad.payment.strategy.PaymentStrategy.class);
        when(paymentRepository.findById(1L)).thenReturn(Optional.of(payment));
        when(paymentStrategyFactory.getStrategy(Payment.PaymentMethod.CREDIT_CARD)).thenReturn(strategy);
        when(strategy.process(any(PaymentContext.class))).thenReturn(payment);
        when(paymentRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        assertThrows(BusinessException.class, () -> service.processPayment(1L, "idem-key"));
        verify(paymentIdempotencyService).failPaymentProcess("idem-key");
    }

    @Test
    void processPayment_runtimeError_marksIdempotencyFailed() {
        when(paymentRepository.findById(1L)).thenReturn(Optional.of(payment));
        when(paymentStrategyFactory.getStrategy(Payment.PaymentMethod.CREDIT_CARD))
                .thenThrow(new IllegalStateException("strategy down"));
        payment.setGatewayAmount(90.0);

        assertThrows(IllegalStateException.class, () -> service.processPayment(1L, "idem-key"));
        verify(paymentIdempotencyService).failPaymentProcess("idem-key");
    }

    @Test
    void processPayment_gatewayFailure_marksFailedAndSchedulesDunningRetry() {
        when(paymentRepository.findById(1L)).thenReturn(Optional.of(payment));
        when(paymentRepository.save(any(Payment.class))).thenAnswer(inv -> inv.getArgument(0));
        when(paymentStrategyFactory.getStrategy(Payment.PaymentMethod.CREDIT_CARD))
                .thenThrow(new IllegalStateException("gateway unavailable"));
        payment.setGatewayAmount(90.0);

        assertThrows(IllegalStateException.class, () -> service.processPayment(1L, "idem-key"));

        // The payment is marked FAILED and queued for the dunning retry loop so a
        // transient gateway outage self-heals.
        assertEquals(Payment.PaymentStatus.FAILED, payment.getStatus());
        verify(dunningService).scheduleRetry(1L);
    }

    // ==================== getPaymentByOrderId / getPaymentForOrder ====================

    @Test
    void getPaymentByOrderId_returnsNullWhenMissing() {
        when(paymentRepository.findByOrderId(1L)).thenReturn(Optional.empty());

        assertNull(service.getPaymentByOrderId(1L));
    }

    @Test
    void getPaymentForOrder_orderNotFound_throws() {
        when(orderRepository.findByIdWithDetails(1L)).thenReturn(Optional.empty());

        assertThrows(ResourceNotFoundException.class, () -> service.getPaymentForOrder(1L));
    }

    @Test
    void getPaymentForOrder_notOwner_throwsUnauthorized() {
        Customer other = new Customer();
        other.setId(2L);
        order.setCustomer(other);
        when(orderRepository.findByIdWithDetails(1L)).thenReturn(Optional.of(order));
        when(securityUtils.getCurrentUserId()).thenReturn(1L);

        assertThrows(UnauthorizedException.class, () -> service.getPaymentForOrder(1L));
    }

    @Test
    void getPaymentForOrder_missingPayment_throws() {
        when(orderRepository.findByIdWithDetails(1L)).thenReturn(Optional.of(order));
        when(securityUtils.getCurrentUserId()).thenReturn(1L);
        when(paymentRepository.findByOrderId(1L)).thenReturn(Optional.empty());

        assertThrows(ResourceNotFoundException.class, () -> service.getPaymentForOrder(1L));
    }

    // ==================== refundPayment edge paths ====================

    @Test
    void refundPayment_alreadyRefunded_returnsSilently() {
        payment.setStatus(Payment.PaymentStatus.REFUNDED);
        when(paymentRepository.findByIdWithLock(1L)).thenReturn(Optional.of(payment));

        service.refundPayment(1L);

        verify(paymentRepository, never()).save(any());
    }

    @Test
    void refundPayment_paymentNotFound_throws() {
        when(paymentRepository.findByIdWithLock(1L)).thenReturn(Optional.empty());

        assertThrows(ResourceNotFoundException.class, () -> service.refundPayment(1L));
    }

    @Test
    void refundPayment_walletMethodWithoutWalletAmount_creditsFullAmount() {
        payment.setPaymentMethod(Payment.PaymentMethod.WALLET);
        payment.setWalletAmount(null);
        payment.setGatewayAmount(null);
        payment.setStatus(Payment.PaymentStatus.COMPLETED);
        payment.setAmount(120.0);
        when(paymentRepository.findByIdWithLock(1L)).thenReturn(Optional.of(payment));
        when(paymentRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        service.refundPayment(1L);

        verify(walletService).credit(eq(1L), eq(120.0),
                eq(WalletTransaction.TransactionType.ORDER_REFUND), eq(payment.getId()), anyString());
        assertEquals(Payment.PaymentStatus.REFUNDED, payment.getStatus());
    }

    @Test
    void refundPayment_bnpl_releasesPendingBalance() {
        payment.setPaymentMethod(Payment.PaymentMethod.BNPL);
        payment.setWalletAmount(null);
        payment.setGatewayAmount(null);
        payment.setStatus(Payment.PaymentStatus.COMPLETED);
        payment.setAmount(200.0);
        when(paymentRepository.findByIdWithLock(1L)).thenReturn(Optional.of(payment));
        when(paymentRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        service.refundPayment(1L);

        // No wallet/gateway money moves, but the customer's outstanding BNPL
        // pending balance (the Redis counter gating the credit limit) is
        // released so a cancelled order cannot permanently consume BNPL credit.
        verify(bnplStrategy).releaseBalance(1L, 200.0);
        verify(walletService, never()).credit(any(), anyDouble(), any(), any(), anyString());
        assertEquals(Payment.PaymentStatus.REFUNDED, payment.getStatus());
    }

    @Test
    void refundPayment_gatewayRefundFailure_throws() {
        payment.setStatus(Payment.PaymentStatus.COMPLETED);
        payment.setWalletAmount(0.0);
        payment.setGatewayAmount(100.0);
        payment.setPaymentMethod(Payment.PaymentMethod.CREDIT_CARD);
        payment.setGatewayPaymentId("pay_1");
        payment.setAmount(100.0);
        when(paymentRepository.findByIdWithLock(1L)).thenReturn(Optional.of(payment));
        when(paymentGateway.refundPayment(any())).thenReturn(CompletableFuture.completedFuture(
                PaymentGateway.GatewayRefundResult.builder()
                        .refundId("rf-1")
                        .success(false)
                        .rawResponse("{}")
                        .build()));

        assertThrows(BusinessException.class, () -> service.refundPayment(1L));
    }

    @Test
    void refundPayment_gatewayRefundSuccess_recordsResponseAndTimeline() {
        payment.setStatus(Payment.PaymentStatus.COMPLETED);
        payment.setWalletAmount(20.0);
        payment.setGatewayAmount(80.0);
        payment.setPaymentMethod(Payment.PaymentMethod.CREDIT_CARD);
        payment.setGatewayPaymentId("pay_2");
        payment.setAmount(100.0);
        order.setStatus(Order.OrderStatus.CONFIRMED);
        when(paymentRepository.findByIdWithLock(1L)).thenReturn(Optional.of(payment));
        when(paymentGateway.refundPayment(any())).thenReturn(CompletableFuture.completedFuture(
                PaymentGateway.GatewayRefundResult.builder()
                        .refundId("rf-2")
                        .success(true)
                        .rawResponse("{\"refunded\":true}")
                        .build()));
        when(paymentRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        service.refundPayment(1L);

        assertEquals("{\"refunded\":true}", payment.getPaymentGatewayResponse());
        verify(walletService).credit(eq(1L), eq(20.0),
                eq(WalletTransaction.TransactionType.ORDER_REFUND), eq(payment.getId()), anyString());
        verify(orderTimelineService).recordEvent(eq(1L), eq("ORDER_REFUNDED"), any(), anyString(), any(), anyString());
        verify(notificationService).sendPaymentRefunded(1L, 100.0);
    }

    @Test
    void refundPayment_timelineFailure_doesNotFailRefund() {
        payment.setStatus(Payment.PaymentStatus.PENDING);
        payment.setWalletAmount(10.0);
        payment.setGatewayAmount(0.0);
        payment.setPaymentMethod(Payment.PaymentMethod.UPI);
        payment.setAmount(110.0);
        order.setStatus(Order.OrderStatus.PLACED);
        when(paymentRepository.findByIdWithLock(1L)).thenReturn(Optional.of(payment));
        when(paymentRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        doThrow(new RuntimeException("timeline down"))
                .when(orderTimelineService).recordEvent(any(), anyString(), any(), anyString(), any(), anyString());

        service.refundPayment(1L);

        assertEquals(Payment.PaymentStatus.REFUNDED, payment.getStatus());
        verify(notificationService).sendPaymentRefunded(1L, 110.0);
    }
}
