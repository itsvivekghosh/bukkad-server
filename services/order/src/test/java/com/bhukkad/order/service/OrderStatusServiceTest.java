package com.bhukkad.order.service;

import com.bhukkad.common.error.BusinessException;
import com.bhukkad.common.error.ResourceNotFoundException;
import com.bhukkad.order.domain.Order;
import com.bhukkad.order.domain.OrderRepository;
import com.bhukkad.order.domain.OrderTimelineEventRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class OrderStatusServiceTest {

    @Mock private OrderRepository orderRepository;
    @Mock private OrderTimelineEventRepository timelineRepository;
    @Mock private OrderEventPublisher eventPublisher;
    @InjectMocks private OrderStatusService service;

    private Order order(String status) {
        Order o = new Order();
        o.setId(5L);
        o.setStatus(status);
        return o;
    }

    @Test
    void transition_validPath_savesTimelineAndPublishes() {
        Order created = order(Order.STATUS_CREATED);
        when(orderRepository.findById(5L)).thenReturn(Optional.of(created));

        Order result = service.transition(5L, Order.STATUS_CONFIRMED);

        assertThat(result.getStatus()).isEqualTo(Order.STATUS_CONFIRMED);
        verify(timelineRepository).save(any());
        verify(eventPublisher).orderStatusChanged(5L, Order.STATUS_CONFIRMED);
    }

    @Test
    void transition_invalidPath_throws() {
        Order delivered = order(Order.STATUS_DELIVERED);
        when(orderRepository.findById(5L)).thenReturn(Optional.of(delivered));

        assertThatThrownBy(() -> service.transition(5L, Order.STATUS_CONFIRMED))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("Invalid transition");
    }

    @Test
    void transition_terminalToAnything_throws() {
        Order cancelled = order(Order.STATUS_CANCELLED);
        when(orderRepository.findById(5L)).thenReturn(Optional.of(cancelled));

        assertThatThrownBy(() -> service.transition(5L, Order.STATUS_OUT_FOR_DELIVERY))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    void transition_preparingToReadyForPickup_allowed() {
        Order preparing = order(Order.STATUS_PREPARING);
        when(orderRepository.findById(5L)).thenReturn(Optional.of(preparing));

        Order result = service.transition(5L, Order.STATUS_READY_FOR_PICKUP);

        assertThat(result.getStatus()).isEqualTo(Order.STATUS_READY_FOR_PICKUP);
    }

    @Test
    void transition_unknownOrder_throws() {
        when(orderRepository.findById(99L)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.transition(99L, Order.STATUS_CONFIRMED))
                .isInstanceOf(ResourceNotFoundException.class);
    }
}
