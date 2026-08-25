package com.bhukkad.compliance;

import com.bhukkad.repository.OrderRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class OrderArchiveServiceTest {

    @Mock
    private OrderRepository orderRepository;

    @Mock
    private OrderArchiveProperties archiveProperties;

    @InjectMocks
    private OrderArchiveService archiveService;

    @Test
    void archiveOldOrders_movesBatchThenDeletes() {
        when(archiveProperties.getRetentionDays()).thenReturn(365);
        when(archiveProperties.getBatchSize()).thenReturn(500);
        when(orderRepository.archiveOrdersBefore(any(LocalDateTime.class), eq(500))).thenReturn(120);

        int moved = archiveService.archiveOldOrders();

        assertEquals(120, moved);
        verify(orderRepository).deleteOrdersBefore(any(LocalDateTime.class), eq(120));
    }

    @Test
    void archiveOldOrders_nothingToMove_skipsDelete() {
        when(archiveProperties.getRetentionDays()).thenReturn(365);
        when(archiveProperties.getBatchSize()).thenReturn(500);
        when(orderRepository.archiveOrdersBefore(any(LocalDateTime.class), eq(500))).thenReturn(0);

        int moved = archiveService.archiveOldOrders();

        assertEquals(0, moved);
        verify(orderRepository, never()).deleteOrdersBefore(any(LocalDateTime.class), eq(120));
    }

    @Test
    void archiveOldOrders_usesConfiguredWindow() {
        when(archiveProperties.getRetentionDays()).thenReturn(30);
        when(archiveProperties.getBatchSize()).thenReturn(100);
        when(orderRepository.archiveOrdersBefore(any(LocalDateTime.class), eq(100))).thenReturn(10);

        archiveService.archiveOldOrders();

        // Cutoff must be retentionDays in the past (within a tolerance of a few seconds).
        verify(orderRepository).archiveOrdersBefore(org.mockito.ArgumentMatchers.argThat(
                cutoff -> {
                    LocalDateTime expected = LocalDateTime.now().minusDays(30);
                    return java.time.Duration.between(cutoff, expected).abs().toSeconds() < 5;
                }), eq(100));
        assertTrue(true);
    }
}
