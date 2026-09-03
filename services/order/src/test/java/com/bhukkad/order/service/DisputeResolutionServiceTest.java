package com.bhukkad.order.service;

import com.bhukkad.common.error.BusinessException;
import com.bhukkad.common.error.ResourceNotFoundException;
import com.bhukkad.common.event.PlatformEventPublisher;
import com.bhukkad.order.api.DisputeRequest;
import com.bhukkad.order.api.DisputeResponse;
import com.bhukkad.order.api.DisputeResolveRequest;
import com.bhukkad.order.domain.Dispute;
import com.bhukkad.order.domain.DisputeRepository;
import com.bhukkad.order.domain.Order;
import com.bhukkad.order.domain.OrderRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DisputeResolutionServiceTest {

    @Mock private DisputeRepository disputeRepository;
    @Mock private OrderRepository orderRepository;
    @Mock private PlatformEventPublisher eventPublisher;

    @InjectMocks private DisputeResolutionService service;

    private Order order(long id, long customerId, String status, BigDecimal total,
                        LocalDateTime deliveredAt, LocalDateTime estimatedDeliveryAt) {
        Order o = new Order();
        o.setId(id);
        o.setCustomerId(customerId);
        o.setStatus(status);
        o.setTotalAmount(total);
        o.setDeliveredAt(deliveredAt);
        o.setEstimatedDeliveryAt(estimatedDeliveryAt);
        return o;
    }

    @Test
    void fileDispute_createsAndAutoResolvesOrderNotReceived() {
        Order order = order(1L, 5L, Order.STATUS_DELIVERED, new BigDecimal("500"),
                LocalDateTime.now(), null);
        when(orderRepository.findById(1L)).thenReturn(Optional.of(order));
        when(disputeRepository.existsByOrderId(1L)).thenReturn(false);
        when(disputeRepository.save(any(Dispute.class))).thenAnswer(inv -> {
            Dispute d = inv.getArgument(0);
            d.setId(10L);
            return d;
        });

        DisputeResponse response = service.fileDispute(5L, 1L,
                new DisputeRequest("ORDER_NOT_RECEIVED", "Never got my food"));

        assertThat(response.id()).isEqualTo(10L);
        assertThat(response.status()).isEqualTo("AUTO_RESOLVED");
        assertThat(response.resolution()).isEqualTo("FULL_REFUND");
        assertThat(response.refundAmount()).isEqualByComparingTo("500.00");
    }

    @Test
    void fileDispute_createsAndMarksLateDeliveryUnderReview_whenNotLate() {
        Order order = order(1L, 5L, Order.STATUS_DELIVERED, new BigDecimal("500"),
                LocalDateTime.now(), LocalDateTime.now());
        when(orderRepository.findById(1L)).thenReturn(Optional.of(order));
        when(disputeRepository.existsByOrderId(1L)).thenReturn(false);
        when(disputeRepository.save(any(Dispute.class))).thenAnswer(inv -> {
            Dispute d = inv.getArgument(0);
            d.setId(11L);
            return d;
        });

        DisputeResponse response = service.fileDispute(5L, 1L,
                new DisputeRequest("LATE_DELIVERY", "It was 5 mins late"));

        assertThat(response.id()).isEqualTo(11L);
        assertThat(response.status()).isEqualTo("UNDER_REVIEW");
    }

    @Test
    void fileDispute_autoResolvesLateDelivery_whenLate() {
        Order order = order(1L, 5L, Order.STATUS_DELIVERED, new BigDecimal("1000"),
                LocalDateTime.now().plusMinutes(45), LocalDateTime.now());
        when(orderRepository.findById(1L)).thenReturn(Optional.of(order));
        when(disputeRepository.existsByOrderId(1L)).thenReturn(false);
        when(disputeRepository.save(any(Dispute.class))).thenAnswer(inv -> {
            Dispute d = inv.getArgument(0);
            d.setId(12L);
            return d;
        });

        DisputeResponse response = service.fileDispute(5L, 1L,
                new DisputeRequest("LATE_DELIVERY", "Very late"));

        assertThat(response.status()).isEqualTo("AUTO_RESOLVED");
        assertThat(response.resolution()).isEqualTo("PARTIAL_REFUND");
        // 10% of 1000 = 100, capped at 100
        assertThat(response.refundAmount()).isEqualByComparingTo("100.00");
    }

    @Test
    void fileDispute_otherType_goesUnderReview() {
        Order order = order(1L, 5L, Order.STATUS_DELIVERED, new BigDecimal("500"),
                LocalDateTime.now(), null);
        when(orderRepository.findById(1L)).thenReturn(Optional.of(order));
        when(disputeRepository.existsByOrderId(1L)).thenReturn(false);
        when(disputeRepository.save(any(Dispute.class))).thenAnswer(inv -> {
            Dispute d = inv.getArgument(0);
            d.setId(13L);
            return d;
        });

        DisputeResponse response = service.fileDispute(5L, 1L,
                new DisputeRequest("FOOD_QUALITY", "Food was cold"));

        assertThat(response.status()).isEqualTo("UNDER_REVIEW");
    }

    @Test
    void fileDispute_wrongCustomer_throws() {
        Order order = order(1L, 5L, Order.STATUS_DELIVERED, new BigDecimal("500"),
                null, null);
        when(orderRepository.findById(1L)).thenReturn(Optional.of(order));

        assertThatThrownBy(() -> service.fileDispute(99L, 1L,
                new DisputeRequest("OTHER", "Bad")))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("not belong");
    }

    @Test
    void fileDispute_duplicateDispute_throws() {
        Order order = order(1L, 5L, Order.STATUS_DELIVERED, new BigDecimal("500"),
                null, null);
        when(orderRepository.findById(1L)).thenReturn(Optional.of(order));
        when(disputeRepository.existsByOrderId(1L)).thenReturn(true);

        assertThatThrownBy(() -> service.fileDispute(5L, 1L,
                new DisputeRequest("OTHER", "Bad")))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("already exists");
    }

    @Test
    void fileDispute_cancelledOrder_throws() {
        Order order = order(1L, 5L, Order.STATUS_CANCELLED, new BigDecimal("500"),
                null, null);
        when(orderRepository.findById(1L)).thenReturn(Optional.of(order));

        assertThatThrownBy(() -> service.fileDispute(5L, 1L,
                new DisputeRequest("OTHER", "Bad")))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("cancelled");
    }

    @Test
    void getById_returnsDispute() {
        Dispute dispute = new Dispute();
        dispute.setId(1L);
        dispute.setOrderId(10L);
        dispute.setType(Dispute.DisputeType.OTHER);
        dispute.setStatus(Dispute.DisputeStatus.OPEN);
        when(disputeRepository.findById(1L)).thenReturn(Optional.of(dispute));
        Order order = order(10L, 5L, Order.STATUS_DELIVERED, new BigDecimal("500"),
                null, null);
        when(orderRepository.findById(10L)).thenReturn(Optional.of(order));

        DisputeResponse response = service.getById(1L);

        assertThat(response.id()).isEqualTo(1L);
        assertThat(response.orderId()).isEqualTo(10L);
    }

    @Test
    void getById_unknown_throws() {
        when(disputeRepository.findById(9L)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.getById(9L))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessageContaining("9");
    }

    @Test
    void manualResolve_fullRefund_resolvesAndPublishes() {
        Order order = order(1L, 5L, Order.STATUS_DELIVERED, new BigDecimal("500"),
                null, null);
        when(orderRepository.findById(1L)).thenReturn(Optional.of(order));
        Dispute dispute = new Dispute();
        dispute.setId(1L);
        dispute.setOrderId(1L);
        dispute.setType(Dispute.DisputeType.OTHER);
        dispute.setStatus(Dispute.DisputeStatus.UNDER_REVIEW);
        when(disputeRepository.findById(1L)).thenReturn(Optional.of(dispute));
        when(disputeRepository.save(any(Dispute.class))).thenAnswer(inv -> inv.getArgument(0));

        DisputeResponse response = service.manualResolve(10L, 1L,
                new DisputeResolveRequest("FULL_REFUND", new BigDecimal("500"), "Approved"));

        assertThat(response.status()).isEqualTo("MANUAL_RESOLVED");
        assertThat(response.resolution()).isEqualTo("FULL_REFUND");
        assertThat(response.refundAmount()).isEqualByComparingTo("500.00");
        assertThat(response.resolvedBy()).isEqualTo(10L);
        verify(eventPublisher).publish(any());
    }

    @Test
    void manualResolve_closedDispute_throws() {
        Dispute closed = new Dispute();
        closed.setId(1L);
        closed.setStatus(Dispute.DisputeStatus.CLOSED);
        when(disputeRepository.findById(1L)).thenReturn(Optional.of(closed));

        assertThatThrownBy(() -> service.manualResolve(10L, 1L,
                new DisputeResolveRequest("NO_REFUND", null, "")))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("already closed");
    }

    @Test
    void manualResolve_invalidResolution_throws() {
        Dispute dispute = new Dispute();
        dispute.setId(1L);
        dispute.setOrderId(1L);
        dispute.setStatus(Dispute.DisputeStatus.UNDER_REVIEW);
        when(disputeRepository.findById(1L)).thenReturn(Optional.of(dispute));

        assertThatThrownBy(() -> service.manualResolve(10L, 1L,
                new DisputeResolveRequest("INVALID", null, "")))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("Invalid resolution");
    }

    @Test
    void listForCustomer_returnsDisputesForCustomerOrders() {
        Order order = order(1L, 5L, Order.STATUS_DELIVERED, new BigDecimal("500"),
                null, null);
        when(orderRepository.findByCustomerId(5L)).thenReturn(List.of(order));
        when(disputeRepository.findByOrderIdInOrderByCreatedAtDesc(List.of(1L)))
                .thenReturn(List.of());

        List<DisputeResponse> result = service.listForCustomer(5L);

        assertThat(result).isEmpty();
    }

    @Test
    void listForAdmin_returnsAllDisputes() {
        when(disputeRepository.findAllByOrderByCreatedAtDesc()).thenReturn(List.of());
        List<DisputeResponse> result = service.listForAdmin();
        assertThat(result).isEmpty();
    }

    @Test
    void triggerAutoResolution_sweepsOpenDisputes() {
        Dispute dispute = new Dispute();
        dispute.setId(1L);
        dispute.setOrderId(1L);
        dispute.setStatus(Dispute.DisputeStatus.OPEN);
        when(disputeRepository.findByStatusInOrderByCreatedAtAsc(any()))
                .thenReturn(List.of(dispute));
        Order order = order(1L, 5L, Order.STATUS_DELIVERED, new BigDecimal("500"),
                null, null);
        when(orderRepository.findById(1L)).thenReturn(Optional.of(order));
        when(disputeRepository.save(any(Dispute.class))).thenAnswer(inv -> inv.getArgument(0));

        int resolved = service.triggerAutoResolution();

        assertThat(resolved).isEqualTo(1);
    }
}