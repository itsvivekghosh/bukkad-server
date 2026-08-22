package com.bhukkad.support;

import com.bhukkad.dto.request.DisputeRequest;
import com.bhukkad.dto.request.DisputeResolveRequest;
import com.bhukkad.entity.Customer;
import com.bhukkad.entity.Dispute;
import com.bhukkad.entity.Order;
import com.bhukkad.entity.Payment;
import com.bhukkad.entity.User;
import com.bhukkad.exception.BusinessException;
import com.bhukkad.exception.ResourceNotFoundException;
import com.bhukkad.repository.DisputeRepository;
import com.bhukkad.repository.OrderRepository;
import com.bhukkad.repository.UserRepository;
import com.bhukkad.wallet.WalletService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class DisputeResolutionServiceTest {

    @Mock
    private DisputeRepository disputeRepository;
    @Mock
    private OrderRepository orderRepository;
    @Mock
    private UserRepository userRepository;
    @Mock
    private WalletService walletService;

    @InjectMocks
    private DisputeResolutionService service;

    @BeforeEach
    void setUp() {
        ReflectionTestUtils.setField(service, "lateThresholdMinutes", 30L);
    }

    private Customer customer(long id) {
        Customer customer = new Customer();
        customer.setId(id);
        customer.setEmail("cust" + id + "@bhukkad.test");
        return customer;
    }

    private Payment completedPayment() {
        Payment payment = new Payment();
        payment.setId(1L);
        payment.setStatus(Payment.PaymentStatus.COMPLETED);
        return payment;
    }

    private Order deliveredPaidOrder(long id, Customer customer, double total) {
        Order order = new Order();
        order.setId(id);
        order.setOrderNumber("BK-" + id);
        order.setCustomer(customer);
        order.setStatus(Order.OrderStatus.DELIVERED);
        order.setTotalAmount(total);
        order.setPayment(completedPayment());
        order.setDeliveredAt(LocalDateTime.now());
        order.setEstimatedDeliveryAt(LocalDateTime.now().minusMinutes(5));
        return order;
    }

    private DisputeRequest request(String type) {
        DisputeRequest request = new DisputeRequest();
        request.setType(type);
        request.setCustomerEvidence("Photo attached showing the delivery bag is empty");
        return request;
    }

    @Test
    void fileDispute_orderNotOwned_throwsBusinessException() {
        Customer other = customer(99L);
        Order order = deliveredPaidOrder(1L, other, 500.0);
        when(orderRepository.findByIdWithDetails(1L)).thenReturn(Optional.of(order));

        assertThrows(BusinessException.class, () -> service.fileDispute(7L, 1L, request("ORDER_NOT_RECEIVED")));
    }

    @Test
    void fileDispute_duplicateDispute_throwsBusinessException() {
        Customer customer = customer(7L);
        Order order = deliveredPaidOrder(1L, customer, 500.0);
        when(orderRepository.findByIdWithDetails(1L)).thenReturn(Optional.of(order));
        when(disputeRepository.existsByOrderId(1L)).thenReturn(true);

        assertThrows(BusinessException.class, () -> service.fileDispute(7L, 1L, request("ORDER_NOT_RECEIVED")));
    }

    @Test
    void fileDispute_cancelledOrder_throwsBusinessException() {
        Customer customer = customer(7L);
        Order order = deliveredPaidOrder(1L, customer, 500.0);
        order.setStatus(Order.OrderStatus.CANCELLED);
        when(orderRepository.findByIdWithDetails(1L)).thenReturn(Optional.of(order));

        assertThrows(BusinessException.class, () -> service.fileDispute(7L, 1L, request("ORDER_NOT_RECEIVED")));
    }

    @Test
    void fileDispute_unknownOrder_throwsNotFound() {
        when(orderRepository.findByIdWithDetails(1L)).thenReturn(Optional.empty());

        assertThrows(ResourceNotFoundException.class, () -> service.fileDispute(7L, 1L, request("ORDER_NOT_RECEIVED")));
    }

    @Test
    void fileDispute_invalidType_throwsBusinessException() {
        Customer customer = customer(7L);
        Order order = deliveredPaidOrder(1L, customer, 500.0);
        when(orderRepository.findByIdWithDetails(1L)).thenReturn(Optional.of(order));
        when(disputeRepository.existsByOrderId(1L)).thenReturn(false);

        assertThrows(BusinessException.class, () -> service.fileDispute(7L, 1L, request("ALIEN_INVASION")));
    }

    @Test
    void fileDispute_orderNotReceivedDeliveredPaid_autoResolvesFullRefund() {
        Customer customer = customer(7L);
        Order order = deliveredPaidOrder(1L, customer, 500.0);
        when(orderRepository.findByIdWithDetails(1L)).thenReturn(Optional.of(order));
        when(disputeRepository.existsByOrderId(1L)).thenReturn(false);
        when(disputeRepository.save(any(Dispute.class))).thenAnswer(inv -> inv.getArgument(0));

        var response = service.fileDispute(7L, 1L, request("ORDER_NOT_RECEIVED"));

        assertEquals("AUTO_RESOLVED", response.getStatus());
        assertEquals("FULL_REFUND", response.getResolution());
        assertEquals(500.0, response.getRefundAmount());
        verify(walletService).credit(eq(customer), eq(500.0),
                eq(com.bhukkad.entity.WalletTransaction.TransactionType.ORDER_REFUND),
                eq(order.getPayment()), anyString());
    }

    @Test
    void fileDispute_orderNotReceivedButNotPaid_goesUnderReview() {
        Customer customer = customer(7L);
        Order order = deliveredPaidOrder(1L, customer, 500.0);
        order.getPayment().setStatus(Payment.PaymentStatus.PENDING);
        when(orderRepository.findByIdWithDetails(1L)).thenReturn(Optional.of(order));
        when(disputeRepository.existsByOrderId(1L)).thenReturn(false);
        when(disputeRepository.save(any(Dispute.class))).thenAnswer(inv -> inv.getArgument(0));

        var response = service.fileDispute(7L, 1L, request("ORDER_NOT_RECEIVED"));

        assertEquals("UNDER_REVIEW", response.getStatus());
        assertNull(response.getResolution());
        verify(walletService, never()).credit(any(), anyDouble(), any(), any(), anyString());
    }

    @Test
    void fileDispute_lateDeliveryOverThreshold_autoResolvesPartialRefund() {
        Customer customer = customer(7L);
        Order order = deliveredPaidOrder(1L, customer, 500.0);
        order.setDeliveredAt(LocalDateTime.now());
        order.setEstimatedDeliveryAt(LocalDateTime.now().minusMinutes(60)); // 60 min late
        when(orderRepository.findByIdWithDetails(1L)).thenReturn(Optional.of(order));
        when(disputeRepository.existsByOrderId(1L)).thenReturn(false);
        when(disputeRepository.save(any(Dispute.class))).thenAnswer(inv -> inv.getArgument(0));

        var response = service.fileDispute(7L, 1L, request("LATE_DELIVERY"));

        assertEquals("AUTO_RESOLVED", response.getStatus());
        assertEquals("PARTIAL_REFUND", response.getResolution());
        assertEquals(50.0, response.getRefundAmount()); // 10% of 500, capped at 100
    }

    @Test
    void fileDispute_lateDeliveryWithinThreshold_goesUnderReview() {
        Customer customer = customer(7L);
        Order order = deliveredPaidOrder(1L, customer, 500.0);
        order.setDeliveredAt(LocalDateTime.now());
        order.setEstimatedDeliveryAt(LocalDateTime.now().minusMinutes(10)); // only 10 min late
        when(orderRepository.findByIdWithDetails(1L)).thenReturn(Optional.of(order));
        when(disputeRepository.existsByOrderId(1L)).thenReturn(false);
        when(disputeRepository.save(any(Dispute.class))).thenAnswer(inv -> inv.getArgument(0));

        var response = service.fileDispute(7L, 1L, request("LATE_DELIVERY"));

        assertEquals("UNDER_REVIEW", response.getStatus());
        verify(walletService, never()).credit(any(), anyDouble(), any(), any(), anyString());
    }

    @Test
    void fileDispute_foodQuality_goesUnderReview() {
        Customer customer = customer(7L);
        Order order = deliveredPaidOrder(1L, customer, 500.0);
        when(orderRepository.findByIdWithDetails(1L)).thenReturn(Optional.of(order));
        when(disputeRepository.existsByOrderId(1L)).thenReturn(false);
        when(disputeRepository.save(any(Dispute.class))).thenAnswer(inv -> inv.getArgument(0));

        var response = service.fileDispute(7L, 1L, request("FOOD_QUALITY"));

        assertEquals("UNDER_REVIEW", response.getStatus());
        verify(walletService, never()).credit(any(), anyDouble(), any(), any(), anyString());
    }

    @Test
    void manualResolve_fullRefund_appliesRefundAndCloses() {
        Customer customer = customer(7L);
        Order order = deliveredPaidOrder(1L, customer, 500.0);
        Dispute dispute = new Dispute();
        dispute.setId(1L);
        dispute.setOrder(order);
        dispute.setType(Dispute.DisputeType.WRONG_ORDER);
        dispute.setStatus(Dispute.DisputeStatus.UNDER_REVIEW);

        when(disputeRepository.findById(1L)).thenReturn(Optional.of(dispute));
        User admin = new User();
        admin.setId(9L);
        when(userRepository.findById(9L)).thenReturn(Optional.of(admin));
        when(disputeRepository.save(any(Dispute.class))).thenAnswer(inv -> inv.getArgument(0));

        DisputeResolveRequest resolve = new DisputeResolveRequest();
        resolve.setResolution("FULL_REFUND");
        resolve.setRefundAmount(500.0);
        resolve.setNotes("Customer photos confirm non-delivery");

        var response = service.manualResolve(9L, 1L, resolve);

        assertEquals("MANUAL_RESOLVED", response.getStatus());
        assertEquals("FULL_REFUND", response.getResolution());
        assertEquals(500.0, response.getRefundAmount());
        assertEquals(9L, response.getResolvedBy());
        verify(walletService).credit(eq(customer), eq(500.0),
                eq(com.bhukkad.entity.WalletTransaction.TransactionType.ORDER_REFUND),
                eq(order.getPayment()), anyString());
    }

    @Test
    void manualResolve_fullRefundAboveTotal_throwsBusinessException() {
        Customer customer = customer(7L);
        Order order = deliveredPaidOrder(1L, customer, 500.0);
        Dispute dispute = new Dispute();
        dispute.setId(1L);
        dispute.setOrder(order);
        dispute.setType(Dispute.DisputeType.WRONG_ORDER);
        dispute.setStatus(Dispute.DisputeStatus.UNDER_REVIEW);

        when(disputeRepository.findById(1L)).thenReturn(Optional.of(dispute));

        DisputeResolveRequest resolve = new DisputeResolveRequest();
        resolve.setResolution("FULL_REFUND");
        resolve.setRefundAmount(900.0);

        assertThrows(BusinessException.class, () -> service.manualResolve(9L, 1L, resolve));
        verify(walletService, never()).credit(any(), anyDouble(), any(), any(), anyString());
    }

    @Test
    void manualResolve_partialRefundRequiresPositiveAmount() {
        Customer customer = customer(7L);
        Order order = deliveredPaidOrder(1L, customer, 500.0);
        Dispute dispute = new Dispute();
        dispute.setId(1L);
        dispute.setOrder(order);
        dispute.setType(Dispute.DisputeType.WRONG_ORDER);
        dispute.setStatus(Dispute.DisputeStatus.UNDER_REVIEW);

        when(disputeRepository.findById(1L)).thenReturn(Optional.of(dispute));

        DisputeResolveRequest resolve = new DisputeResolveRequest();
        resolve.setResolution("PARTIAL_REFUND");
        resolve.setRefundAmount(null);

        assertThrows(BusinessException.class, () -> service.manualResolve(9L, 1L, resolve));
        verify(walletService, never()).credit(any(), anyDouble(), any(), any(), anyString());
    }

    @Test
    void manualResolve_noRefund_doesNotTouchWallet() {
        Customer customer = customer(7L);
        Order order = deliveredPaidOrder(1L, customer, 500.0);
        Dispute dispute = new Dispute();
        dispute.setId(1L);
        dispute.setOrder(order);
        dispute.setType(Dispute.DisputeType.WRONG_ORDER);
        dispute.setStatus(Dispute.DisputeStatus.OPEN);

        when(disputeRepository.findById(1L)).thenReturn(Optional.of(dispute));
        User admin = new User();
        admin.setId(9L);
        when(userRepository.findById(9L)).thenReturn(Optional.of(admin));
        when(disputeRepository.save(any(Dispute.class))).thenAnswer(inv -> inv.getArgument(0));

        DisputeResolveRequest resolve = new DisputeResolveRequest();
        resolve.setResolution("NO_REFUND");
        resolve.setNotes("Evidence insufficient");

        var response = service.manualResolve(9L, 1L, resolve);

        assertEquals("MANUAL_RESOLVED", response.getStatus());
        assertNull(response.getRefundAmount());
        verify(walletService, never()).credit(any(), anyDouble(), any(), any(), anyString());
    }

    @Test
    void manualResolve_closedDispute_throwsBusinessException() {
        Customer customer = customer(7L);
        Order order = deliveredPaidOrder(1L, customer, 500.0);
        Dispute dispute = new Dispute();
        dispute.setId(1L);
        dispute.setOrder(order);
        dispute.setType(Dispute.DisputeType.WRONG_ORDER);
        dispute.setStatus(Dispute.DisputeStatus.CLOSED);

        when(disputeRepository.findById(1L)).thenReturn(Optional.of(dispute));

        DisputeResolveRequest resolve = new DisputeResolveRequest();
        resolve.setResolution("NO_REFUND");

        assertThrows(BusinessException.class, () -> service.manualResolve(9L, 1L, resolve));
    }

    @Test
    void manualResolve_invalidResolution_throwsBusinessException() {
        Customer customer = customer(7L);
        Order order = deliveredPaidOrder(1L, customer, 500.0);
        Dispute dispute = new Dispute();
        dispute.setId(1L);
        dispute.setOrder(order);
        dispute.setType(Dispute.DisputeType.WRONG_ORDER);
        dispute.setStatus(Dispute.DisputeStatus.OPEN);

        when(disputeRepository.findById(1L)).thenReturn(Optional.of(dispute));

        DisputeResolveRequest resolve = new DisputeResolveRequest();
        resolve.setResolution("GIVE_EVERYTHING_FREE");

        assertThrows(BusinessException.class, () -> service.manualResolve(9L, 1L, resolve));
    }

    @Test
    void triggerAutoResolution_resolvesEligibleAndQueuesRest() {
        Customer customer = customer(7L);
        Order eligibleOrder = deliveredPaidOrder(1L, customer, 400.0);
        Dispute eligible = new Dispute();
        eligible.setId(1L);
        eligible.setOrder(eligibleOrder);
        eligible.setType(Dispute.DisputeType.ORDER_NOT_RECEIVED);
        eligible.setCustomerEvidence("Evidence");
        eligible.setStatus(Dispute.DisputeStatus.OPEN);

        Order ineligibleOrder = deliveredPaidOrder(2L, customer, 200.0);
        ineligibleOrder.getPayment().setStatus(Payment.PaymentStatus.PENDING);
        Dispute ineligible = new Dispute();
        ineligible.setId(2L);
        ineligible.setOrder(ineligibleOrder);
        ineligible.setType(Dispute.DisputeType.FOOD_QUALITY);
        ineligible.setCustomerEvidence("Evidence");
        ineligible.setStatus(Dispute.DisputeStatus.UNDER_REVIEW);

        when(disputeRepository.findByStatusInOrderByCreatedAtAsc(anyList()))
                .thenReturn(List.of(eligible, ineligible));
        when(disputeRepository.save(any(Dispute.class))).thenAnswer(inv -> inv.getArgument(0));

        int resolved = service.triggerAutoResolution();

        assertEquals(1, resolved);
        assertEquals(Dispute.DisputeStatus.AUTO_RESOLVED, eligible.getStatus());
        assertEquals(Dispute.DisputeStatus.UNDER_REVIEW, ineligible.getStatus());
        verify(walletService).credit(eq(customer), eq(400.0),
                eq(com.bhukkad.entity.WalletTransaction.TransactionType.ORDER_REFUND),
                eq(eligibleOrder.getPayment()), anyString());
    }

    @Test
    void listForCustomer_returnsOwnDisputes() {
        Customer customer = customer(7L);
        Order order = deliveredPaidOrder(1L, customer, 400.0);
        Dispute dispute = new Dispute();
        dispute.setId(1L);
        dispute.setOrder(order);
        dispute.setType(Dispute.DisputeType.OTHER);
        dispute.setStatus(Dispute.DisputeStatus.OPEN);
        dispute.setCreatedAt(LocalDateTime.now());

        when(disputeRepository.findByOrderCustomerIdOrderByCreatedAtDesc(7L))
                .thenReturn(List.of(dispute));

        var result = service.listForCustomer(7L);

        assertEquals(1, result.size());
        assertEquals(1L, result.get(0).getOrderId());
        assertEquals("BK-1", result.get(0).getOrderNumber());
    }
}
