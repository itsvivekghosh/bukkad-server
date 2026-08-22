package com.bhukkad.serviceImpl;

import com.bhukkad.dto.response.OrderResponse;
import com.bhukkad.entity.Customer;
import com.bhukkad.entity.Order;
import com.bhukkad.mapper.OrderMapper;
import com.bhukkad.repository.OrderRepository;
import com.bhukkad.security.SecurityUtils;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.lang.reflect.Field;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
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
