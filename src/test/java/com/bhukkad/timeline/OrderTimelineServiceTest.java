package com.bhukkad.timeline;

import com.bhukkad.dto.response.OrderTimelineEventResponse;
import com.bhukkad.entity.Order;
import com.bhukkad.entity.OrderTimelineEvent;
import com.bhukkad.exception.ResourceNotFoundException;
import com.bhukkad.repository.OrderRepository;
import com.bhukkad.repository.OrderTimelineEventRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link OrderTimelineService} — the audit trail behind order
 * tracking and support tickets. Covers event recording, chronological reads,
 * and the not-found paths.
 */
@ExtendWith(MockitoExtension.class)
class OrderTimelineServiceTest {

    @Mock
    private OrderTimelineEventRepository orderTimelineEventRepository;

    @Mock
    private OrderRepository orderRepository;

    @InjectMocks
    private OrderTimelineService orderTimelineService;

    private Order sampleOrder(Long id) {
        Order order = new Order();
        order.setId(id);
        return order;
    }

    @Test
    void recordEvent_savesEventWithAllFields() {
        Order order = sampleOrder(5L);
        when(orderRepository.findById(5L)).thenReturn(Optional.of(order));

        OrderTimelineEvent saved = new OrderTimelineEvent();
        saved.setId(1L);
        saved.setOrder(order);
        saved.setEventType("ORDER_PLACED");
        saved.setStatus("PLACED");
        saved.setMessage("Order placed successfully");
        saved.setActorId(42L);
        saved.setActorRole("CUSTOMER");
        saved.setCreatedAt(LocalDateTime.of(2026, 8, 22, 14, 0));
        when(orderTimelineEventRepository.save(any(OrderTimelineEvent.class)))
                .thenAnswer(inv -> {
                    OrderTimelineEvent e = inv.getArgument(0);
                    e.setId(1L);
                    return e;
                });

        OrderTimelineEventResponse response = orderTimelineService.recordEvent(
                5L, "ORDER_PLACED", "PLACED", "Order placed successfully", 42L, "CUSTOMER");

        assertNotNull(response);
        assertEquals(5L, response.getOrderId());
        assertEquals("ORDER_PLACED", response.getEventType());
        assertEquals("PLACED", response.getStatus());
        assertEquals("Order placed successfully", response.getMessage());
        assertEquals(42L, response.getActorId());
        assertEquals("CUSTOMER", response.getActorRole());
        verify(orderTimelineEventRepository).save(any(OrderTimelineEvent.class));
    }

    @Test
    void recordEvent_orderNotFound_throws() {
        when(orderRepository.findById(99L)).thenReturn(Optional.empty());

        assertThrows(ResourceNotFoundException.class,
                () -> orderTimelineService.recordEvent(99L, "T", "S", "M", null, null));
    }

    @Test
    void getTimelineForOrder_returnsChronologicalEvents() {
        when(orderRepository.existsById(5L)).thenReturn(true);

        OrderTimelineEvent e1 = new OrderTimelineEvent();
        e1.setId(1L);
        e1.setOrder(sampleOrder(5L));
        e1.setEventType("ORDER_PLACED");
        e1.setStatus("PLACED");
        e1.setMessage("Placed");

        OrderTimelineEvent e2 = new OrderTimelineEvent();
        e2.setId(2L);
        e2.setOrder(sampleOrder(5L));
        e2.setEventType("ORDER_DELIVERED");
        e2.setStatus("DELIVERED");
        e2.setMessage("Delivered");

        when(orderTimelineEventRepository.findByOrderIdOrderByCreatedAtAsc(5L))
                .thenReturn(List.of(e1, e2));

        List<OrderTimelineEventResponse> timeline = orderTimelineService.getTimelineForOrder(5L);

        assertEquals(2, timeline.size());
        assertEquals("ORDER_PLACED", timeline.get(0).getEventType());
        assertEquals("ORDER_DELIVERED", timeline.get(1).getEventType());
        assertEquals(5L, timeline.get(0).getOrderId());
    }

    @Test
    void getTimelineForOrder_orderNotFound_throws() {
        when(orderRepository.existsById(99L)).thenReturn(false);

        assertThrows(ResourceNotFoundException.class,
                () -> orderTimelineService.getTimelineForOrder(99L));
    }

    @Test
    void getTimelineForOrder_emptyOrder_returnsEmptyList() {
        when(orderRepository.existsById(5L)).thenReturn(true);
        when(orderTimelineEventRepository.findByOrderIdOrderByCreatedAtAsc(5L))
                .thenReturn(List.of());

        List<OrderTimelineEventResponse> timeline = orderTimelineService.getTimelineForOrder(5L);
        assertNotNull(timeline);
        assertTrue(timeline.isEmpty());
    }
}
