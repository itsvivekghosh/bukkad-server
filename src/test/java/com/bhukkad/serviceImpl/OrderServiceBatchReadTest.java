package com.bhukkad.serviceImpl;

import com.bhukkad.dto.response.OrderResponse;
import com.bhukkad.dto.response.OrderSummaryResponse;
import com.bhukkad.entity.Customer;
import com.bhukkad.entity.Order;
import com.bhukkad.entity.Restaurant;
import com.bhukkad.entity.RestaurantOwner;
import com.bhukkad.entity.User;
import com.bhukkad.exception.UnauthorizedException;
import com.bhukkad.mapper.OrderMapper;
import com.bhukkad.repository.OrderRepository;
import com.bhukkad.repository.RestaurantRepository;
import com.bhukkad.security.SecurityUtils;
import com.bhukkad.util.CursorUtils;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.lang.reflect.Field;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.when;

/**
 * Unit coverage for {@code OrderServiceImpl.getOrdersByIds}.
 *
 * <p>Pins the three behaviours callers rely on:
 * <ul>
 *   <li>a {@code null}/{@code empty} ids collection returns an empty map
 *       instead of triggering a query against the database;</li>
 *   <li>orders that do not belong to the caller are dropped silently rather
 *       than thrown — this is the documented auth response for batch reads;</li>
 *   <li>orders that DO belong to the caller are returned keyed by id.</li>
 * </ul>
 *
 * <p>{@code @InjectMocks} uses the only-arg constructor that Mockito picks
 * for the fewest required collaborators; uninteresting dependencies are left
 * null because {@code getOrdersByIds} never touches them.
 */
@ExtendWith(MockitoExtension.class)
class OrderServiceBatchReadTest {

    @Mock private OrderRepository orderRepository;
    @Mock private RestaurantRepository restaurantRepository;
    @Mock private SecurityUtils securityUtils;
    @Mock private OrderMapper orderMapper;

    @InjectMocks private OrderServiceImpl service;

    @Test
    void nullIds_returnsEmptyMapWithoutQuerying() {
        Map<Long, OrderResponse> result = service.getOrdersByIds(null);
        assertNotNull(result);
        assertTrue(result.isEmpty());
    }

    @Test
    void emptyIds_returnsEmptyMapWithoutQuerying() {
        Map<Long, OrderResponse> result = service.getOrdersByIds(List.of());
        assertNotNull(result);
        assertTrue(result.isEmpty());
    }

    @Test
    void mixedOwnership_dropsOthersOrdersSilently() {
        when(securityUtils.getCurrentUserId()).thenReturn(42L);

        Order mine = order(100L, 42L);
        Order notMine = order(200L, 99L);
        when(orderRepository.findAllById(any())).thenReturn(List.of(mine, notMine));

        OrderResponse mineResp = new OrderResponse();
        mineResp.setId(100L);
        when(orderMapper.toResponse(mine)).thenReturn(mineResp);

        Map<Long, OrderResponse> result = service.getOrdersByIds(List.of(100L, 200L));

        assertEquals(1, result.size(), "must drop orders not owned by the caller");
        assertTrue(result.containsKey(100L));
        assertFalse(result.containsKey(200L), "caller must not see another customer's order id");
    }

    private Order order(long id, long customerId) {
        try {
            Order o = new Order();
            setField(o, "id", id);
            Customer c = new Customer();
            setField(c, "id", customerId);
            setField(o, "customer", c);
            return o;
        } catch (Exception ex) {
            throw new RuntimeException(ex);
        }
    }

    // ==================== cursor-based reads ====================

    @Test
    void getCustomerOrdersByCursor_firstPage_noNextCursor() {
        when(securityUtils.getCurrentUserId()).thenReturn(42L);
        List<OrderSummaryResponse> batch = List.of(summary(1L), summary(2L));
        when(orderRepository.findCustomerOrderSummariesAfterCursor(eq(42L), isNull(), isNull(), any()))
                .thenReturn(batch);

        var result = service.getCustomerOrdersByCursor(null, 10);

        assertFalse(result.isHasNext());
        assertEquals(2, result.getItems().size());
        assertNull(result.getNextCursor());
    }

    @Test
    void getCustomerOrdersByCursor_fullBatch_yieldsNextCursor() {
        when(securityUtils.getCurrentUserId()).thenReturn(42L);
        // size+1 rows returned => one extra row signals another page
        List<OrderSummaryResponse> batch = List.of(summary(1L), summary(2L), summary(3L));
        when(orderRepository.findCustomerOrderSummariesAfterCursor(eq(42L), isNull(), isNull(), any()))
                .thenReturn(batch);

        var result = service.getCustomerOrdersByCursor(null, 2);

        assertTrue(result.isHasNext());
        assertEquals(2, result.getItems().size());
        assertNotNull(result.getNextCursor());
    }

    @Test
    void getCustomerOrdersByCursor_withCursor_decodesAndQueries() {
        when(securityUtils.getCurrentUserId()).thenReturn(42L);
        String cursor = CursorUtils.encode(LocalDateTime.of(2026, 1, 1, 10, 0), 55L);
        when(orderRepository.findCustomerOrderSummariesAfterCursor(
                eq(42L), eq(LocalDateTime.of(2026, 1, 1, 10, 0)), eq(55L), any()))
                .thenReturn(List.of());

        var result = service.getCustomerOrdersByCursor(cursor, 5);

        assertFalse(result.isHasNext());
    }

    @Test
    void getRestaurantOrdersByCursor_verifiesOwnership_thenQueries() {
        when(securityUtils.getCurrentUserId()).thenReturn(7L);
        Restaurant restaurant = new Restaurant();
        RestaurantOwner owner = new RestaurantOwner();
        owner.setId(7L);
        restaurant.setOwner(owner);
        when(restaurantRepository.findByIdWithDetails(10L)).thenReturn(Optional.of(restaurant));
        when(orderRepository.findRestaurantOrderSummariesAfterCursor(eq(10L), isNull(), isNull(), any()))
                .thenReturn(List.of());

        var result = service.getRestaurantOrdersByCursor(10L, null, 5);

        assertFalse(result.isHasNext());
    }

    @Test
    void getRestaurantOrdersByCursor_notOwner_throws() {
        when(securityUtils.getCurrentUserId()).thenReturn(7L);
        Restaurant restaurant = new Restaurant();
        RestaurantOwner owner = new RestaurantOwner();
        owner.setId(99L);
        restaurant.setOwner(owner);
        when(restaurantRepository.findByIdWithDetails(10L)).thenReturn(Optional.of(restaurant));

        assertThrows(UnauthorizedException.class,
                () -> service.getRestaurantOrdersByCursor(10L, null, 5));
    }

    @Test
    void getDeliveryAgentOrdersByCursor_currentAgent_queriesWithOwnId() {
        User agent = new User();
        agent.setId(31L);
        agent.setRole(User.UserRole.DELIVERY_AGENT);
        when(securityUtils.getCurrentUser()).thenReturn(agent);
        when(orderRepository.findDeliveryAgentOrderSummariesAfterCursor(eq(31L), isNull(), isNull(), any()))
                .thenReturn(List.of(summary(9L)));

        var result = service.getDeliveryAgentOrdersByCursor(null, null, 5);

        assertEquals(1, result.getItems().size());
        assertFalse(result.isHasNext());
    }

    @Test
    void getDeliveryAgentOrdersByCursor_otherAgentId_throws() {
        User agent = new User();
        agent.setId(31L);
        agent.setRole(User.UserRole.DELIVERY_AGENT);
        when(securityUtils.getCurrentUser()).thenReturn(agent);

        assertThrows(UnauthorizedException.class,
                () -> service.getDeliveryAgentOrdersByCursor(88L, null, 5));
    }

    @Test
    void getDeliveryAgentOrdersByCursor_nonAgentAccount_throws() {
        User customer = new User();
        customer.setId(5L);
        customer.setRole(User.UserRole.CUSTOMER);
        when(securityUtils.getCurrentUser()).thenReturn(customer);

        assertThrows(UnauthorizedException.class,
                () -> service.getDeliveryAgentOrdersByCursor(null, null, 5));
    }

    private OrderSummaryResponse summary(long id) {
        OrderSummaryResponse s = new OrderSummaryResponse();
        s.setId(id);
        s.setCreatedAt(LocalDateTime.now());
        return s;
    }

    private static void setField(Object target, String name, Object value) throws Exception {
        Field f = findField(target.getClass(), name);
        f.setAccessible(true);
        f.set(target, value);
    }

    private static Field findField(Class<?> type, String name) throws NoSuchFieldException {
        for (Class<?> c = type; c != null; c = c.getSuperclass()) {
            try { return c.getDeclaredField(name); }
            catch (NoSuchFieldException ignored) {}
        }
        throw new NoSuchFieldException(name);
    }
}
