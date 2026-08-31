package com.bhukkad.payment;

import com.bhukkad.config.SettlementProperties;
import com.bhukkad.entity.DeliveryAgent;
import com.bhukkad.entity.Order;
import com.bhukkad.entity.Restaurant;
import com.bhukkad.entity.RestaurantSettlement;
import com.bhukkad.event.OrderSettledEvent;
import com.bhukkad.exception.BusinessException;
import com.bhukkad.outbox.OutboxEventService;
import com.bhukkad.repository.RestaurantSettlementRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * Settlement tests. Since the modular boundary (DomainBoundaryArchTest), the
 * payment domain no longer writes the delivery aggregate directly: the rider
 * earning is recorded by the delivery domain's listener from the ORDER_SETTLED
 * outbox event.
 */
@ExtendWith(MockitoExtension.class)
class SplitSettlementServiceTest {

    @Mock private RestaurantSettlementRepository restaurantSettlementRepository;
    @Mock private OutboxEventService outboxEventService;

    private SettlementProperties settlementProperties;
    private SplitSettlementService service;

    @BeforeEach
    void setUp() {
        settlementProperties = new SettlementProperties();
        settlementProperties.setCommissionPercent(15.0);
        service = new SplitSettlementService(restaurantSettlementRepository,
                settlementProperties, outboxEventService);
    }

    @Test
    void settle_nullOrder_throwsBusiness() {
        assertThrows(BusinessException.class, () -> service.settle(null));
        assertThrows(BusinessException.class, () -> service.settle(new Order()));
    }

    @Test
    void settle_recordsRestaurantSettlementAndPublishesSettledEvent() {
        Order order = new Order();
        order.setId(10L);
        order.setOrderNumber("ORD-10");
        order.setTotalAmount(200.0);
        order.setSubtotal(180.0);
        order.setTipAmount(20.0);
        Restaurant restaurant = new Restaurant();
        restaurant.setId(1L);
        order.setRestaurant(restaurant);
        DeliveryAgent agent = new DeliveryAgent();
        agent.setId(3L);
        order.setDeliveryAgent(agent);

        when(restaurantSettlementRepository.existsByOrderId(10L)).thenReturn(false);

        service.settle(order);

        verify(restaurantSettlementRepository).save(argThat(s ->
                s.getOrderAmount() == 200.0
                        && s.getCommissionAmount() == 30.0
                        && s.getNetAmount() == 150.0
                        && s.getStatus() == RestaurantSettlement.SettlementStatus.PENDING));

        // Cross-domain write went through the outbox, not the delivery aggregate
        ArgumentCaptor<Object> payload = ArgumentCaptor.forClass(Object.class);
        verify(outboxEventService).enqueue(eq("ORDER_SETTLED"), eq(10L), payload.capture());
        OrderSettledEvent event = (OrderSettledEvent) payload.getValue();
        assertEquals(10L, event.orderId());
        assertEquals(3L, event.deliveryAgentId());
        assertEquals(1L, event.restaurantId());
        assertEquals(20.0, event.tipAmount());
        verify(restaurantSettlementRepository, times(1)).save(any());
    }

    @Test
    void settle_skipsWhenAlreadyRecorded() {
        Order order = new Order();
        order.setId(10L);
        order.setTotalAmount(100.0);
        order.setSubtotal(90.0);
        order.setDeliveryAgent(new DeliveryAgent());

        when(restaurantSettlementRepository.existsByOrderId(10L)).thenReturn(true);

        service.settle(order);

        verify(restaurantSettlementRepository, never()).save(any());
        verify(outboxEventService, never()).enqueue(any(), any(), any());
    }

    @Test
    void settle_withoutDeliveryAgent_skipsRiderEarningEvent() {
        Order order = new Order();
        order.setId(11L);
        order.setTotalAmount(100.0);
        order.setSubtotal(90.0);
        Restaurant restaurant = new Restaurant();
        restaurant.setId(1L);
        order.setRestaurant(restaurant);

        when(restaurantSettlementRepository.existsByOrderId(11L)).thenReturn(false);

        service.settle(order);

        verify(restaurantSettlementRepository).save(any());
        verify(outboxEventService, never()).enqueue(any(), any(), any());
    }

    @Test
    void settle_nullAmounts_fallBackToSubtotalAndZero() {
        Order order = new Order();
        order.setId(12L);
        order.setSubtotal(80.0);
        order.setRestaurant(new Restaurant());

        when(restaurantSettlementRepository.existsByOrderId(12L)).thenReturn(false);

        service.settle(order);

        verify(restaurantSettlementRepository).save(argThat(s -> s.getOrderAmount() == 80.0));
        verify(outboxEventService, never()).enqueue(any(), any(), any());
    }
}
