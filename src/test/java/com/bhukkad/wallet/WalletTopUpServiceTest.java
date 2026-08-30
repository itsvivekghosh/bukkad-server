package com.bhukkad.wallet;

import com.bhukkad.dto.response.PaymentResponse;
import com.bhukkad.entity.Customer;
import com.bhukkad.entity.Order;
import com.bhukkad.entity.Payment;
import com.bhukkad.entity.WalletTransaction;
import com.bhukkad.exception.BusinessException;
import com.bhukkad.payment.PaymentGateway;
import com.bhukkad.payment.PaymentProperties;
import com.bhukkad.repository.CustomerRepository;
import com.bhukkad.repository.PaymentRepository;
import com.bhukkad.security.SecurityUtils;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class WalletTopUpServiceTest {

    @Mock
    private CustomerRepository customerRepository;

    @Mock
    private PaymentRepository paymentRepository;

    @Mock
    private PaymentGateway paymentGateway;

    @Mock
    private PaymentProperties paymentProperties;

    @Mock
    private WalletService walletService;

    @Mock
    private SecurityUtils securityUtils;

    @InjectMocks
    private WalletTopUpService service;

    private PaymentProperties.Razorpay razorpay;

    @BeforeEach
    void setUp() {
        razorpay = new PaymentProperties.Razorpay();
        razorpay.setEnabled(true);
        razorpay.setCurrency("INR");
        lenient().when(paymentProperties.getRazorpay()).thenReturn(razorpay);
    }

    private Customer customer(Long id) {
        Customer customer = new Customer();
        customer.setId(id);
        customer.setWalletBalance(50.0);
        return customer;
    }

    private Payment walletTopUpPayment(Long id) {
        Payment payment = new Payment();
        payment.setId(id);
        payment.setPurpose(Payment.PaymentPurpose.WALLET_TOP_UP);
        payment.setPaymentMethod(Payment.PaymentMethod.UPI);
        payment.setStatus(Payment.PaymentStatus.PENDING);
        payment.setAmount(200.0);
        payment.setGatewayOrderId("go_9");
        payment.setGatewayPaymentId("gp_9");
        payment.setTransactionId("tx_9");
        payment.setCustomer(customer(1L));
        return payment;
    }

    // ---------- initiateTopUp ----------

    @Test
    void initiateTopUp_nullAmount_throws() {
        assertThrows(BusinessException.class, () -> service.initiateTopUp(null, "key-1"));
    }

    @Test
    void initiateTopUp_zeroAmount_throws() {
        assertThrows(BusinessException.class, () -> service.initiateTopUp(0.0, "key-1"));
    }

    @Test
    void initiateTopUp_negativeAmount_throws() {
        assertThrows(BusinessException.class, () -> service.initiateTopUp(-10.0, "key-1"));
    }

    @Test
    void initiateTopUp_razorpayDisabled_throws() {
        razorpay.setEnabled(false);

        assertThrows(BusinessException.class, () -> service.initiateTopUp(100.0, "key-1"));
    }

    @Test
    void initiateTopUp_existingIdempotencyKey_returnsExistingPayment() {
        Payment existing = walletTopUpPayment(5L);
        when(paymentRepository.findByIdempotencyKey("key-1")).thenReturn(Optional.of(existing));

        PaymentResponse response = service.initiateTopUp(100.0, "key-1");

        assertEquals(5L, response.getId());
        assertEquals("UPI", response.getPaymentMethod());
        assertEquals("PENDING", response.getStatus());
        assertEquals(200.0, response.getAmount());
        assertEquals("go_9", response.getGatewayOrderId());
        assertEquals("gp_9", response.getGatewayPaymentId());
        assertEquals("tx_9", response.getTransactionId());
        assertEquals("WALLET_TOP_UP", response.getPurpose());
        verify(paymentGateway, never()).createOrder(any());
        verify(paymentRepository, never()).save(any());
    }

    @Test
    void initiateTopUp_existingPaymentWithOrder_mapsOrderId() {
        Payment existing = walletTopUpPayment(5L);
        Order order = new Order();
        order.setId(77L);
        existing.setOrder(order);
        when(paymentRepository.findByIdempotencyKey("key-1")).thenReturn(Optional.of(existing));

        PaymentResponse response = service.initiateTopUp(100.0, "key-1");

        assertEquals(77L, response.getOrderId());
    }

    @Test
    void initiateTopUp_blankIdempotencyKey_skipsExistingCheck() {
        when(securityUtils.getCurrentUserId()).thenReturn(1L);
        when(customerRepository.findById(1L)).thenReturn(Optional.of(customer(1L)));
        when(paymentGateway.createOrder(any())).thenReturn(CompletableFuture.completedFuture(
                PaymentGateway.GatewayOrderResult.builder()
                        .gatewayOrderId("go_new")
                        .rawResponse("{\"id\":\"go_new\"}")
                        .build()));
        when(paymentRepository.save(any(Payment.class))).thenAnswer(inv -> {
            Payment p = inv.getArgument(0);
            p.setId(9L);
            return p;
        });

        PaymentResponse response = service.initiateTopUp(100.0, "   ");

        verify(paymentRepository, never()).findByIdempotencyKey(anyString());
        assertEquals(9L, response.getId());
        assertEquals("go_new", response.getGatewayOrderId());
    }

    @Test
    void initiateTopUp_nullIdempotencyKey_skipsExistingCheck() {
        when(securityUtils.getCurrentUserId()).thenReturn(1L);
        when(customerRepository.findById(1L)).thenReturn(Optional.of(customer(1L)));
        when(paymentGateway.createOrder(any())).thenReturn(CompletableFuture.completedFuture(
                PaymentGateway.GatewayOrderResult.builder()
                        .gatewayOrderId("go_new")
                        .rawResponse("{}")
                        .build()));
        when(paymentRepository.save(any(Payment.class))).thenAnswer(inv -> {
            Payment p = inv.getArgument(0);
            p.setId(9L);
            return p;
        });

        PaymentResponse response = service.initiateTopUp(100.0, null);

        verify(paymentRepository, never()).findByIdempotencyKey(anyString());
        assertNotNull(response);
    }

    @Test
    void initiateTopUp_customerNotFound_throws() {
        when(securityUtils.getCurrentUserId()).thenReturn(1L);
        when(customerRepository.findById(1L)).thenReturn(Optional.empty());

        assertThrows(BusinessException.class, () -> service.initiateTopUp(100.0, "key-1"));
    }

    @Test
    void initiateTopUp_success_createsGatewayOrderAndPersistsPayment() {
        when(securityUtils.getCurrentUserId()).thenReturn(1L);
        when(customerRepository.findById(1L)).thenReturn(Optional.of(customer(1L)));
        when(paymentGateway.createOrder(any())).thenReturn(CompletableFuture.completedFuture(
                PaymentGateway.GatewayOrderResult.builder()
                        .gatewayOrderId("go_123")
                        .rawResponse("{\"id\":\"go_123\"}")
                        .build()));
        when(paymentRepository.save(any(Payment.class))).thenAnswer(inv -> {
            Payment p = inv.getArgument(0);
            p.setId(7L);
            return p;
        });

        PaymentResponse response = service.initiateTopUp(100.0, "key-1");

        ArgumentCaptor<PaymentGateway.GatewayOrderRequest> requestCaptor =
                ArgumentCaptor.forClass(PaymentGateway.GatewayOrderRequest.class);
        verify(paymentGateway).createOrder(requestCaptor.capture());
        PaymentGateway.GatewayOrderRequest gatewayRequest = requestCaptor.getValue();
        assertEquals(100.0, gatewayRequest.amount());
        assertEquals("INR", gatewayRequest.currency());
        assertTrue(gatewayRequest.receipt().startsWith("WALLET-"));
        assertEquals("key-1", gatewayRequest.idempotencyKey());

        ArgumentCaptor<Payment> paymentCaptor = ArgumentCaptor.forClass(Payment.class);
        verify(paymentRepository).save(paymentCaptor.capture());
        Payment saved = paymentCaptor.getValue();
        assertEquals(Payment.PaymentPurpose.WALLET_TOP_UP, saved.getPurpose());
        assertEquals(Payment.PaymentMethod.UPI, saved.getPaymentMethod());
        assertEquals(Payment.PaymentStatus.PENDING, saved.getStatus());
        assertEquals(100.0, saved.getAmount());
        assertEquals(100.0, saved.getGatewayAmount());
        assertEquals(0.0, saved.getWalletAmount());
        assertEquals("go_123", saved.getGatewayOrderId());
        assertEquals("{\"id\":\"go_123\"}", saved.getPaymentGatewayResponse());
        assertEquals("key-1", saved.getIdempotencyKey());
        assertEquals(customer(1L).getId(), saved.getCustomer().getId());

        assertEquals(7L, response.getId());
        assertEquals("WALLET_TOP_UP", response.getPurpose());
        assertEquals("PENDING", response.getStatus());
    }

    @Test
    void initiateTopUp_success_roundsAmountToTwoDecimals() {
        when(securityUtils.getCurrentUserId()).thenReturn(1L);
        when(customerRepository.findById(1L)).thenReturn(Optional.of(customer(1L)));
        when(paymentGateway.createOrder(any())).thenReturn(CompletableFuture.completedFuture(
                PaymentGateway.GatewayOrderResult.builder()
                        .gatewayOrderId("go_123")
                        .rawResponse("{}")
                        .build()));
        when(paymentRepository.save(any(Payment.class))).thenAnswer(inv -> inv.getArgument(0));

        service.initiateTopUp(123.456, "key-1");

        ArgumentCaptor<PaymentGateway.GatewayOrderRequest> requestCaptor =
                ArgumentCaptor.forClass(PaymentGateway.GatewayOrderRequest.class);
        verify(paymentGateway).createOrder(requestCaptor.capture());
        assertEquals(123.46, requestCaptor.getValue().amount());
    }

    // ---------- completeTopUp ----------

    @Test
    void completeTopUp_wrongPurpose_doesNothing() {
        Payment payment = walletTopUpPayment(1L);
        payment.setPurpose(Payment.PaymentPurpose.ORDER);

        service.completeTopUp(payment, "gp_abc");

        verifyNoInteractions(paymentRepository, walletService);
    }

    @Test
    void completeTopUp_alreadyCompleted_doesNothing() {
        Payment payment = walletTopUpPayment(1L);
        payment.setStatus(Payment.PaymentStatus.COMPLETED);

        service.completeTopUp(payment, "gp_abc");

        verify(paymentRepository, never()).save(any());
        verifyNoInteractions(walletService);
    }

    @Test
    void completeTopUp_success_marksCompletedAndCreditsWallet() {
        Payment payment = walletTopUpPayment(1L);

        service.completeTopUp(payment, "gp_abc");

        assertEquals("gp_abc", payment.getGatewayPaymentId());
        assertEquals("gp_abc", payment.getTransactionId());
        assertEquals(Payment.PaymentStatus.COMPLETED, payment.getStatus());
        assertNotNull(payment.getCompletedAt());
        verify(paymentRepository).save(payment);
        verify(walletService).credit(
                eq(1L),
                eq(200.0),
                eq(WalletTransaction.TransactionType.TOP_UP),
                eq(payment.getId()),
                eq("Wallet top-up via payment gateway"));
    }
}
