package com.bhukkad.settlement;

import com.bhukkad.config.SettlementProperties;
import com.bhukkad.dto.response.PagedResponse;
import com.bhukkad.dto.response.RestaurantSettlementResponse;
import com.bhukkad.entity.Order;
import com.bhukkad.entity.Restaurant;
import com.bhukkad.entity.RestaurantSettlement;
import com.bhukkad.common.error.ResourceNotFoundException;
import com.bhukkad.repository.RestaurantRepository;
import com.bhukkad.repository.RestaurantSettlementRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;

import java.lang.reflect.Field;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class RestaurantSettlementServiceTest {

    @Mock
    private RestaurantSettlementRepository settlementRepository;

    @Mock
    private RestaurantRepository restaurantRepository;

    @Mock
    private SettlementProperties settlementProperties;

    @InjectMocks
    private RestaurantSettlementService service;

    @Test
    void recordSettlement_skipsWhenAlreadyExists() {
        when(settlementRepository.existsByOrderId(1L)).thenReturn(true);

        Order order = new Order();
        order.setId(1L);

        service.recordSettlementForDeliveredOrder(order);

        verify(settlementRepository).existsByOrderId(1L);
    }

    @Test
    void recordSettlement_usesOrderSubtotalAndRestaurantCommission() {
        when(settlementRepository.existsByOrderId(1L)).thenReturn(false);

        Restaurant restaurant = new Restaurant();
        restaurant.setId(7L);
        restaurant.setCommissionPercent(10.0);

        Order order = new Order();
        order.setId(1L);
        order.setSubtotal(500.0);
        order.setTotalAmount(600.0);
        order.setRestaurant(restaurant);

        service.recordSettlementForDeliveredOrder(order);

        org.mockito.ArgumentCaptor<RestaurantSettlement> captor =
                org.mockito.ArgumentCaptor.forClass(RestaurantSettlement.class);
        verify(settlementRepository).save(captor.capture());
        RestaurantSettlement saved = captor.getValue();
        assertEquals(500.0, saved.getOrderAmount());
        assertEquals(50.0, saved.getCommissionAmount());
        assertEquals(450.0, saved.getNetAmount());
        assertEquals(RestaurantSettlement.SettlementStatus.PENDING, saved.getStatus());
    }

    @Test
    void recordSettlement_fallsBackToTotalAmountAndDefaultCommission() {
        when(settlementRepository.existsByOrderId(1L)).thenReturn(false);
        when(settlementProperties.getCommissionPercent()).thenReturn(15.0);

        Restaurant restaurant = new Restaurant();
        restaurant.setId(7L);
        restaurant.setCommissionPercent(null);

        Order order = new Order();
        order.setId(1L);
        order.setSubtotal(null);
        order.setTotalAmount(200.0);
        order.setRestaurant(restaurant);

        service.recordSettlementForDeliveredOrder(order);

        org.mockito.ArgumentCaptor<RestaurantSettlement> captor =
                org.mockito.ArgumentCaptor.forClass(RestaurantSettlement.class);
        verify(settlementRepository).save(captor.capture());
        RestaurantSettlement saved = captor.getValue();
        assertEquals(200.0, saved.getOrderAmount());
        assertEquals(30.0, saved.getCommissionAmount());
        assertEquals(170.0, saved.getNetAmount());
    }

    @Test
    void getRestaurantSettlements_paged() {
        RestaurantSettlement settlement = settlement(5L, LocalDateTime.now(), 7L, true, true);
        when(settlementRepository.findByRestaurantIdOrderByCreatedAtDesc(eq(7L), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(settlement)));

        PagedResponse<RestaurantSettlementResponse> result = service.getRestaurantSettlements(7L, 0, 10);

        assertNotNull(result);
        assertEquals(1, result.getItems().size());
        RestaurantSettlementResponse item = result.getItems().get(0);
        assertEquals(5L, item.getId());
        assertEquals(7L, item.getRestaurantId());
        assertEquals("ORD-5", item.getOrderNumber());
        assertEquals("PENDING", item.getStatus());
    }

    @Test
    void getRestaurantSettlements_paged_nullTimestamps() {
        RestaurantSettlement settlement = settlement(6L, null, 7L, false, false);
        when(settlementRepository.findByRestaurantIdOrderByCreatedAtDesc(eq(7L), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(settlement)));

        PagedResponse<RestaurantSettlementResponse> result = service.getRestaurantSettlements(7L, 1, 20);

        assertNotNull(result);
        assertNull(result.getItems().get(0).getSettledAt());
        assertNull(result.getItems().get(0).getCreatedAt());
    }

    @Test
    void settlePending_throwsWhenRestaurantMissing() {
        when(restaurantRepository.existsById(99L)).thenReturn(false);

        assertThrows(ResourceNotFoundException.class, () -> service.settlePendingForRestaurant(99L));
    }

    @Test
    void settlePending_marksAllPendingAsSettled() {
        when(restaurantRepository.existsById(7L)).thenReturn(true);
        when(settlementRepository.atomicSettleByRestaurant(eq(7L), eq(RestaurantSettlement.SettlementStatus.PENDING),
                eq(RestaurantSettlement.SettlementStatus.SETTLED), any(LocalDateTime.class))).thenReturn(2);

        int count = service.settlePendingForRestaurant(7L);

        assertEquals(2, count);
        verify(settlementRepository).atomicSettleByRestaurant(eq(7L), eq(RestaurantSettlement.SettlementStatus.PENDING),
                eq(RestaurantSettlement.SettlementStatus.SETTLED), any(LocalDateTime.class));
    }

    @Test
    void settlePending_emptyList() {
        when(restaurantRepository.existsById(7L)).thenReturn(true);
        when(settlementRepository.atomicSettleByRestaurant(eq(7L), eq(RestaurantSettlement.SettlementStatus.PENDING),
                eq(RestaurantSettlement.SettlementStatus.SETTLED), any(LocalDateTime.class))).thenReturn(0);

        int count = service.settlePendingForRestaurant(7L);

        assertEquals(0, count);
    }

    @Test
    void getPendingSettlementAmount_returnsSum() {
        when(settlementRepository.sumNetAmountByRestaurantAndStatus(7L, RestaurantSettlement.SettlementStatus.PENDING))
                .thenReturn(123.45);

        assertEquals(123.45, service.getPendingSettlementAmount(7L));
    }

    @Test
    void getPendingSettlementAmount_returnsZeroWhenNull() {
        when(settlementRepository.sumNetAmountByRestaurantAndStatus(7L, RestaurantSettlement.SettlementStatus.PENDING))
                .thenReturn(null);

        assertEquals(0.0, service.getPendingSettlementAmount(7L));
    }

    @Test
    void toResponse_includesSettledAndCreatedAt() {
        RestaurantSettlement settlement = settlement(9L, LocalDateTime.of(2025, 1, 1, 10, 30), 7L, true, true);
        when(settlementRepository.findByRestaurantIdOrderByCreatedAtDesc(eq(7L), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(settlement)));

        RestaurantSettlementResponse response = service.getRestaurantSettlements(7L, 0, 10)
                .getItems().get(0);

        assertNotNull(response.getSettledAt());
        assertNotNull(response.getCreatedAt());
        assertEquals(settlement.getSettledAt().toString(), response.getSettledAt());
    }

    private RestaurantSettlement settlement(long id, LocalDateTime createdAt, long restaurantId,
                                            boolean withSettledAt, boolean withCreatedAt) {
        try {
            Restaurant restaurant = new Restaurant();
            setField(restaurant, "id", restaurantId);
            Order order = new Order();
            setField(order, "id", id);
            setField(order, "orderNumber", "ORD-" + id);

            RestaurantSettlement s = new RestaurantSettlement();
            setField(s, "id", id);
            if (withCreatedAt) {
                setField(s, "createdAt", createdAt != null ? createdAt : LocalDateTime.now());
            }
            setField(s, "restaurant", restaurant);
            setField(s, "order", order);
            setField(s, "orderAmount", 100.0);
            setField(s, "commissionAmount", 10.0);
            setField(s, "netAmount", 90.0);
            setField(s, "status", RestaurantSettlement.SettlementStatus.PENDING);
            if (withSettledAt) {
                setField(s, "settledAt", LocalDateTime.now());
            }
            return s;
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private static void setField(Object target, String name, Object value) throws Exception {
        Field f = findField(target.getClass(), name);
        f.setAccessible(true);
        f.set(target, value);
    }

    private static Field findField(Class<?> type, String name) throws NoSuchFieldException {
        for (Class<?> c = type; c != null; c = c.getSuperclass()) {
            try {
                return c.getDeclaredField(name);
            } catch (NoSuchFieldException ignored) {
            }
        }
        throw new NoSuchFieldException(name);
    }
}
