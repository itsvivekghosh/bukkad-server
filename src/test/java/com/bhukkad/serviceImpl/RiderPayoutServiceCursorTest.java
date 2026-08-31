package com.bhukkad.serviceImpl;

import com.bhukkad.config.RiderEarningsProperties;
import com.bhukkad.dto.response.CursorPagedResponse;
import com.bhukkad.dto.response.PagedResponse;
import com.bhukkad.dto.response.RiderPayoutResponse;
import com.bhukkad.entity.DeliveryAgent;
import com.bhukkad.entity.Order;
import com.bhukkad.entity.RiderEarning;
import com.bhukkad.exception.ResourceNotFoundException;
import com.bhukkad.repository.DeliveryAgentRepository;
import com.bhukkad.repository.RiderEarningRepository;
import com.bhukkad.security.SecurityUtils;
import com.bhukkad.service.DeliveryService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;

import java.lang.reflect.Field;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit coverage for {@code RiderPayoutServiceImpl.getPayoutHistoryByCursor}.
 *
 * <p>Asserts the cursor round-trip via {@code findByAgentIdAfterCursor} and the
 * {@code hasNext}/{@code nextCursor} contract. The {@code SecurityUtils} mock is
 * needed because the service reads the agent id from the security context — if
 * that wiring ever breaks (e.g. wrong principal), the cursor methods would all
 * silently query for {@code null} agents.
 */
@ExtendWith(MockitoExtension.class)
class RiderPayoutServiceCursorTest {

    @Mock private RiderEarningRepository riderEarningRepository;
    @Mock private SecurityUtils securityUtils;
    @Mock private DeliveryService deliveryService;
    @Mock private RiderEarningsProperties riderEarningsProperties;
    @Mock private DeliveryAgentRepository deliveryAgentRepository;

    @InjectMocks private RiderPayoutServiceImpl service;

    @Test
    void firstPage_oversizeBatch_returnsCursor() {
        when(securityUtils.getCurrentUserId()).thenReturn(42L);
        RiderEarning older = earning(100L, LocalDateTime.now().minusDays(2));
        RiderEarning newer = earning(101L, LocalDateTime.now().minusDays(1));
        when(riderEarningRepository.findByAgentIdAfterCursor(
                eq(42L), isNull(), isNull(), any(Pageable.class)))
                .thenReturn(List.of(newer, older));

        CursorPagedResponse<RiderPayoutResponse> page = service.getPayoutHistoryByCursor(null, 1);

        assertTrue(page.isHasNext());
        assertEquals(1, page.getItems().size());
        assertNotNull(page.getNextCursor());
    }

    @Test
    void lastPage_noCursor() {
        when(securityUtils.getCurrentUserId()).thenReturn(42L);
        when(riderEarningRepository.findByAgentIdAfterCursor(
                eq(42L), isNull(), isNull(), any(Pageable.class)))
                .thenReturn(List.of(earning(100L, LocalDateTime.now())));

        CursorPagedResponse<RiderPayoutResponse> page = service.getPayoutHistoryByCursor(null, 5);

        assertEquals(1, page.getItems().size());
        assertEquals(false, page.isHasNext());
        assertNull(page.getNextCursor());
    }

    private RiderEarning earning(long id, LocalDateTime createdAt) {
        try {
            DeliveryAgent agent = new DeliveryAgent();
            setField(agent, "id", 42L);
            Order order = new Order();
            setField(order, "id", id);
            setField(order, "orderNumber", "ORD-" + id);

            RiderEarning e = new RiderEarning();
            setField(e, "id", id);
            setField(e, "createdAt", createdAt);
            setField(e, "agent", agent);
            setField(e, "order", order);
            setField(e, "amount", 50.0);
            setField(e, "status", RiderEarning.EarningStatus.PAID);
            return e;
        } catch (Exception ex) {
            throw new RuntimeException(ex);
        }
    }

    // ==================== additional coverage ====================

    @Test
    void getEarningsSummary_nullSums_defaultToZero() {
        when(securityUtils.getCurrentUserId()).thenReturn(42L);
        when(riderEarningRepository.sumAmountByAgentIdAndStatus(42L, RiderEarning.EarningStatus.PENDING)).thenReturn(null);
        when(riderEarningRepository.sumAmountByAgentIdAndStatus(42L, RiderEarning.EarningStatus.PAID)).thenReturn(null);
        when(riderEarningsProperties.getPerDelivery()).thenReturn(35.0);
        DeliveryAgent agent = new DeliveryAgent();
        agent.setTotalDeliveries(120);
        when(deliveryService.getCurrentDeliveryAgent()).thenReturn(agent);

        var summary = service.getEarningsSummary();

        assertEquals(0.0, summary.getPendingAmount());
        assertEquals(0.0, summary.getPaidAmount());
        assertEquals(35.0, summary.getPerDeliveryFee());
        assertEquals(120L, summary.getTotalDeliveries());
    }

    @Test
    void getEarningsSummary_withSums_returnsValues() {
        when(securityUtils.getCurrentUserId()).thenReturn(42L);
        when(riderEarningRepository.sumAmountByAgentIdAndStatus(eq(42L), eq(RiderEarning.EarningStatus.PENDING))).thenReturn(150.0);
        when(riderEarningRepository.sumAmountByAgentIdAndStatus(eq(42L), eq(RiderEarning.EarningStatus.PAID))).thenReturn(900.0);
        when(riderEarningsProperties.getPerDelivery()).thenReturn(30.0);
        DeliveryAgent agent = new DeliveryAgent();
        agent.setTotalDeliveries(10);
        when(deliveryService.getCurrentDeliveryAgent()).thenReturn(agent);

        var summary = service.getEarningsSummary();

        assertEquals(150.0, summary.getPendingAmount());
        assertEquals(900.0, summary.getPaidAmount());
    }

    @Test
    void getPayoutHistory_mapsPage() {
        when(securityUtils.getCurrentUserId()).thenReturn(42L);
        Page<RiderEarning> page = new PageImpl<>(List.of(earning(1L, LocalDateTime.now())));
        when(riderEarningRepository.findByAgentIdOrderByCreatedAtDesc(eq(42L), any(Pageable.class)))
                .thenReturn(page);

        PagedResponse<RiderPayoutResponse> result = service.getPayoutHistory(0, 10);

        assertEquals(1, result.getItems().size());
    }

    @Test
    void settlePendingPayouts_marksAllPendingAsPaid() throws Exception {
        // Batch B fix: settlement is now a single atomic UPDATE — one agent
        // existence check plus one bulk UPDATE instead of load-modify-save rows.
        when(deliveryAgentRepository.existsById(42L)).thenReturn(true);
        when(riderEarningRepository.atomicSettleByAgent(
                eq(42L), eq(RiderEarning.EarningStatus.PENDING),
                eq(RiderEarning.EarningStatus.PAID), any(LocalDateTime.class)))
                .thenReturn(1);

        int settled = service.settlePendingPayouts(42L);

        assertEquals(1, settled);
        verify(riderEarningRepository).atomicSettleByAgent(
                eq(42L), eq(RiderEarning.EarningStatus.PENDING),
                eq(RiderEarning.EarningStatus.PAID), any(LocalDateTime.class));
    }

    @Test
    void settlePendingPayouts_agentNotFound_throws() throws Exception {
        when(deliveryAgentRepository.existsById(404L)).thenReturn(false);

        assertThrows(ResourceNotFoundException.class,
                () -> service.settlePendingPayouts(404L));
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
