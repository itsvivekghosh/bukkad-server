package com.bhukkad.payment;

import com.bhukkad.config.RiderEarningsProperties;
import com.bhukkad.config.SettlementProperties;
import com.bhukkad.entity.DeliveryAgent;
import com.bhukkad.entity.Order;
import com.bhukkad.entity.Restaurant;
import com.bhukkad.entity.RestaurantSettlement;
import com.bhukkad.entity.RiderEarning;
import com.bhukkad.exception.BusinessException;
import com.bhukkad.repository.RestaurantSettlementRepository;
import com.bhukkad.repository.RiderEarningRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class SplitSettlementServiceTest {

    @Mock private RestaurantSettlementRepository restaurantSettlementRepository;
    @Mock private RiderEarningRepository riderEarningRepository;

    private SettlementProperties settlementProperties;
    private RiderEarningsProperties riderEarningsProperties;
    private SplitSettlementService service;

    @BeforeEach
    void setUp() {
        settlementProperties = new SettlementProperties();
        settlementProperties.setCommissionPercent(15.0);
        riderEarningsProperties = new RiderEarningsProperties();
        riderEarningsProperties.setPerDelivery(30.0);
        service = new SplitSettlementService(restaurantSettlementRepository, riderEarningRepository,
                settlementProperties, riderEarningsProperties);
    }

    @Test
    void settle_nullOrder_throwsBusiness() {
        assertThrows(BusinessException.class, () -> service.settle(null));
        assertThrows(BusinessException.class, () -> service.settle(new Order()));
    }

    @Test
    void settle_recordsRestaurantAndRiderEarning() {
        Order order = new Order();
        order.setId(10L);
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
        when(riderEarningRepository.existsByOrderId(10L)).thenReturn(false);

        service.settle(order);

        verify(restaurantSettlementRepository).save(argThat(s ->
                s.getOrderAmount() == 200.0
                        && s.getCommissionAmount() == 30.0
                        && s.getNetAmount() == 150.0
                        && s.getStatus() == RestaurantSettlement.SettlementStatus.PENDING));
        verify(riderEarningRepository).save(argThat(e ->
                e.getAmount() == 50.0 && e.getStatus() == RiderEarning.EarningStatus.PENDING));
    }

    @Test
    void settle_skipsWhenAlreadyRecorded() {
        Order order = new Order();
        order.setId(10L);
        order.setTotalAmount(100.0);
        order.setSubtotal(90.0);
        order.setDeliveryAgent(new DeliveryAgent());

        when(restaurantSettlementRepository.existsByOrderId(10L)).thenReturn(true);
        when(riderEarningRepository.existsByOrderId(10L)).thenReturn(true);

        service.settle(order);

        verify(restaurantSettlementRepository, never()).save(any());
        verify(riderEarningRepository, never()).save(any());
    }

    @Test
    void settle_withoutDeliveryAgent_skipsRiderEarning() {
        Order order = new Order();
        order.setId(11L);
        order.setTotalAmount(100.0);
        order.setSubtotal(90.0);

        when(restaurantSettlementRepository.existsByOrderId(11L)).thenReturn(false);

        service.settle(order);

        verify(restaurantSettlementRepository).save(any());
        verify(riderEarningRepository, never()).save(any());
    }

    @Test
    void settle_nullAmounts_fallBackToSubtotalAndZero() {
        Order order = new Order();
        order.setId(12L);
        order.setSubtotal(80.0);

        when(restaurantSettlementRepository.existsByOrderId(12L)).thenReturn(false);

        service.settle(order);

        verify(restaurantSettlementRepository).save(argThat(s -> s.getOrderAmount() == 80.0));
    }
}