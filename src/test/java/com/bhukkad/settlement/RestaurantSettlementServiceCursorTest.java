package com.bhukkad.settlement;

import com.bhukkad.dto.response.CursorPagedResponse;
import com.bhukkad.dto.response.RestaurantSettlementResponse;
import com.bhukkad.entity.RestaurantSettlement;
import com.bhukkad.repository.RestaurantRepository;
import com.bhukkad.repository.RestaurantSettlementRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Pageable;

import java.lang.reflect.Field;
import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.when;

/**
 * Unit coverage for the cursor-paginated settlement lookup. Mirrors the
 * assertions in {@code WalletQueryServiceCursorTest} — cursor round-trip,
 * {@code hasNext} derived from the {@code size + 1} over-fetch, and
 * {@code nextCursor == null} on the last page.
 */
@ExtendWith(MockitoExtension.class)
class RestaurantSettlementServiceCursorTest {

    @Mock private RestaurantSettlementRepository settlementRepository;
    @Mock private RestaurantRepository restaurantRepository;
    @Mock private com.bhukkad.config.SettlementProperties settlementProperties;

    @InjectMocks private RestaurantSettlementService service;

    @Test
    void firstPage_oversizeBatch_producesCursorAndTrimsToSize() {
        // Repository returns size+1 entries to signal "there is more".
        RestaurantSettlement older = settlement(10L, LocalDateTime.now().minusDays(2));
        RestaurantSettlement newer = settlement(11L, LocalDateTime.now().minusDays(1));
        when(settlementRepository.findByRestaurantIdAfterCursor(
                eq(7L), isNull(), isNull(), any(Pageable.class)))
                .thenReturn(List.of(newer, older));

        CursorPagedResponse<RestaurantSettlementResponse> page =
                service.getRestaurantSettlementsByCursor(7L, null, 1);

        assertTrue(page.isHasNext());
        assertEquals(1, page.getItems().size(), "must trim the +1 sentinel");
        assertNotNull(page.getNextCursor());
    }

    @Test
    void lastPage_noSentinel_yieldsNoCursor() {
        RestaurantSettlement only = settlement(10L, LocalDateTime.now());
        when(settlementRepository.findByRestaurantIdAfterCursor(
                eq(7L), isNull(), isNull(), any(Pageable.class)))
                .thenReturn(List.of(only));

        CursorPagedResponse<RestaurantSettlementResponse> page =
                service.getRestaurantSettlementsByCursor(7L, null, 5);

        assertEquals(1, page.getItems().size());
        assertEquals(false, page.isHasNext());
        assertNull(page.getNextCursor());
    }

    private RestaurantSettlement settlement(long id, LocalDateTime createdAt) {
        try {
            com.bhukkad.entity.Restaurant restaurant = new com.bhukkad.entity.Restaurant();
            setField(restaurant, "id", 7L);
            com.bhukkad.entity.Order order = new com.bhukkad.entity.Order();
            setField(order, "id", id);
            setField(order, "orderNumber", "ORD-" + id);

            RestaurantSettlement s = new RestaurantSettlement();
            setField(s, "id", id);
            setField(s, "createdAt", createdAt);
            setField(s, "restaurant", restaurant);
            setField(s, "order", order);
            setField(s, "orderAmount", 100.0);
            setField(s, "commissionAmount", 10.0);
            setField(s, "netAmount", 90.0);
            setField(s, "status", RestaurantSettlement.SettlementStatus.PENDING);
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
            try { return c.getDeclaredField(name); }
            catch (NoSuchFieldException ignored) {}
        }
        throw new NoSuchFieldException(name);
    }
}
