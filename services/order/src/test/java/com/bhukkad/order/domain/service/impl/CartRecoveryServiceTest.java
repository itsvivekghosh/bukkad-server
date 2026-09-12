package com.bhukkad.order.domain.service.impl;

import com.bhukkad.order.domain.repository.CartItemRepository;
import com.bhukkad.order.domain.repository.CartRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.data.domain.Pageable;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.transaction.PlatformTransactionManager;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * PERF-3: sweeper now runs SQL-predicated, batched statements (500 per
 * transaction) instead of the findAll() + per-cart save loop.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class CartRecoveryServiceTest {

    @Mock private CartRepository cartRepository;
    @Mock private CartItemRepository cartItemRepository;
    @Mock private PlatformTransactionManager transactionManager;

    private CartRecoveryService service() {
        // Mocked transaction manager: TransactionTemplate executes the callback
        // inline; the point here is the repository statement choreography.
        return new CartRecoveryService(cartRepository, cartItemRepository, transactionManager, 24);
    }

    @Test
    void expireStale_selectsOnlyStaleActiveViaSqlPredicate() {
        when(cartRepository.findIdsByStatusAndUpdatedAtBefore(
                eq(CartService.STATUS_ACTIVE), any(LocalDateTime.class), any(Pageable.class)))
                .thenReturn(List.of(1L, 2L));
        when(cartRepository.updateStatusByIds(anyList(), eq(CartService.STATUS_CHECKED_OUT),
                any(LocalDateTime.class))).thenReturn(2);

        int expired = service().expireStale(24);

        assertThat(expired).isEqualTo(2);
        // One batch, one delete statement, one bulk status update.
        verify(cartItemRepository, times(1)).deleteByCartIdIn(List.of(1L, 2L));
        verify(cartRepository, times(1)).updateStatusByIds(
                eq(List.of(1L, 2L)), eq(CartService.STATUS_CHECKED_OUT), any(LocalDateTime.class));
    }

    @Test
    void expireStale_batchesAreBoundedPageables() {
        when(cartRepository.findIdsByStatusAndUpdatedAtBefore(
                any(), any(), any(Pageable.class))).thenReturn(List.of());

        service().expireStale(24);

        ArgumentCaptor<Pageable> pageable = ArgumentCaptor.forClass(Pageable.class);
        verify(cartRepository).findIdsByStatusAndUpdatedAtBefore(
                eq(CartService.STATUS_ACTIVE), any(LocalDateTime.class), pageable.capture());
        assertThat(pageable.getValue().getPageSize()).isEqualTo(500);
        assertThat(pageable.getValue().getPageNumber()).isZero();
    }

    @Test
    void expireStale_continuesUntilBatchDrains() {
        List<Long> full = java.util.stream.LongStream.rangeClosed(1, 500).boxed().toList();
        when(cartRepository.findIdsByStatusAndUpdatedAtBefore(
                any(), any(), any(Pageable.class))).thenReturn(full, List.of(999L));
        when(cartRepository.updateStatusByIds(anyList(), any(), any(LocalDateTime.class)))
                .thenReturn(500, 1);

        int expired = service().expireStale(24);

        assertThat(expired).isEqualTo(501);
        verify(cartItemRepository, times(2)).deleteByCartIdIn(anyList());
    }

    @Test
    void expireStale_zeroWhenNothingStale() {
        when(cartRepository.findIdsByStatusAndUpdatedAtBefore(
                any(), any(), any(Pageable.class))).thenReturn(List.of());

        assertThat(service().expireStale(24)).isZero();
        verify(cartItemRepository, never()).deleteByCartIdIn(anyList());
    }

    @Test
    void sweepStaleCarts_isHourlyScheduledWithShedLock() throws Exception {
        var method = CartRecoveryService.class.getMethod("sweepStaleCarts");
        Scheduled scheduled = method.getAnnotation(Scheduled.class);
        assertThat(scheduled).as("wired scheduled sweeper").isNotNull();
        assertThat(scheduled.cron()).isEqualTo("${app.carts.sweeper-cron:0 0 * * * *}");
        var lock = method.getAnnotation(net.javacrumbs.shedlock.spring.annotation.SchedulerLock.class);
        assertThat(lock).as("single-runner across replicas").isNotNull();
        assertThat(lock.name()).isEqualTo("cart-stale-sweeper");
    }

    @Test
    void sweepStaleCarts_appliesConfiguredTtl() {
        when(cartRepository.findIdsByStatusAndUpdatedAtBefore(
                any(), any(LocalDateTime.class), any(Pageable.class))).thenReturn(List.of());
        LocalDateTime before = LocalDateTime.now().minusHours(25);

        service().sweepStaleCarts();

        ArgumentCaptor<LocalDateTime> cutoff = ArgumentCaptor.forClass(LocalDateTime.class);
        verify(cartRepository).findIdsByStatusAndUpdatedAtBefore(
                any(), cutoff.capture(), any(Pageable.class));
        // 24 h TTL → cutoff lands between "now-25h" and "now-23h".
        assertThat(cutoff.getValue()).isBetween(before, LocalDateTime.now().minusHours(23));
    }
}
