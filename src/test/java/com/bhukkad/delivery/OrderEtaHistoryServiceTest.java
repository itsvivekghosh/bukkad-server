package com.bhukkad.delivery;

import com.bhukkad.dto.response.OrderEtaDetailResponse;
import com.bhukkad.entity.Order;
import com.bhukkad.entity.OrderEtaSnapshot;
import com.bhukkad.exception.ResourceNotFoundException;
import com.bhukkad.repository.OrderEtaSnapshotRepository;
import com.bhukkad.repository.OrderRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class OrderEtaHistoryServiceTest {

    @Mock
    private OrderEtaSnapshotRepository snapshotRepository;
    @Mock
    private OrderRepository orderRepository;

    @InjectMocks
    private OrderEtaHistoryService service;

    private Order order;
    private OrderEtaService.EtaSnapshot snapshot;
    private OrderEtaSnapshot latestSnapshot;

    @BeforeEach
    void setUp() {
        order = new Order();
        order.setId(1L);
        order.setLiveEtaMinutes(25);
        order.setLiveEtaAt(LocalDateTime.now().plusMinutes(25));

        snapshot = new OrderEtaService.EtaSnapshot(25, LocalDateTime.now().plusMinutes(25), 20, 30, 1.2, 1.0, "normal");
        latestSnapshot = new OrderEtaSnapshot();
        latestSnapshot.setEtaMinutes(25);
        latestSnapshot.setEtaAt(LocalDateTime.now());
        latestSnapshot.setConfidenceLowMinutes(20);
        latestSnapshot.setConfidenceHighMinutes(30);
        latestSnapshot.setTrafficFactor(1.2);
        latestSnapshot.setSurgeMultiplier(1.0);
        latestSnapshot.setFactorsSummary("normal");
        latestSnapshot.setRecordedAt(LocalDateTime.now());
    }

    @Test
    void recordSnapshot_persistsEntity() {
        service.recordSnapshot(order, snapshot, 1.2, 1.0, "normal");
        verify(snapshotRepository).save(any(OrderEtaSnapshot.class));
    }

    @Test
    void getEtaDetail_returnsDetail_whenOrderExists() {
        when(orderRepository.findById(1L)).thenReturn(Optional.of(order));
        when(snapshotRepository.findByOrderIdOrderByRecordedAtDesc(1L))
                .thenReturn(List.of(latestSnapshot));

        OrderEtaDetailResponse result = service.getEtaDetail(1L);

        assertNotNull(result);
        assertEquals(1L, result.getOrderId());
        assertEquals(25, result.getEtaMinutes());
        assertEquals(1.2, result.getTrafficFactor());
        assertEquals(1, result.getHistory().size());
    }

    @Test
    void getEtaDetail_throws_whenOrderNotFound() {
        when(orderRepository.findById(99L)).thenReturn(Optional.empty());
        assertThrows(ResourceNotFoundException.class, () -> service.getEtaDetail(99L));
    }

    @Test
    void getEtaDetail_worksWithEmptyHistory() {
        when(orderRepository.findById(1L)).thenReturn(Optional.of(order));
        when(snapshotRepository.findByOrderIdOrderByRecordedAtDesc(1L)).thenReturn(List.of());

        OrderEtaDetailResponse result = service.getEtaDetail(1L);

        assertNotNull(result);
        assertEquals(1L, result.getOrderId());
        assertEquals(25, result.getEtaMinutes());
        assertEquals(0, result.getHistory().size());
    }
}