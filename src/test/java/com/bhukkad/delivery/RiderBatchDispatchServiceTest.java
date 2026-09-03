package com.bhukkad.delivery;

import com.bhukkad.dto.response.RiderBatchResponse;
import com.bhukkad.entity.Address;
import com.bhukkad.entity.DeliveryAgent;
import com.bhukkad.entity.Order;
import com.bhukkad.entity.Restaurant;
import com.bhukkad.entity.RiderDeliveryBatch;
import com.bhukkad.entity.RiderDeliveryBatchOrder;
import com.bhukkad.common.error.BusinessException;
import com.bhukkad.common.error.ResourceNotFoundException;
import com.bhukkad.repository.OrderRepository;
import com.bhukkad.repository.RiderDeliveryBatchOrderRepository;
import com.bhukkad.repository.RiderDeliveryBatchRepository;
import com.bhukkad.service.DeliveryService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class RiderBatchDispatchServiceTest {

    @Mock
    private RiderDeliveryBatchRepository batchRepository;
    @Mock
    private RiderDeliveryBatchOrderRepository batchOrderRepository;
    @Mock
    private OrderRepository orderRepository;
    @Mock
    private DeliveryService deliveryService;

    @InjectMocks
    private RiderBatchDispatchService dispatchService;

    @Test
    void createBatchFromActiveOrders_emptyOrders_throwsBusinessException() {
        DeliveryAgent agent = agent(7L);
        when(deliveryService.getCurrentDeliveryAgent()).thenReturn(agent);
        when(orderRepository.findByDeliveryAgentIdAndStatusIn(eq(7L), anyList())).thenReturn(List.of());

        BusinessException ex = assertThrows(BusinessException.class,
                () -> dispatchService.createBatchFromActiveOrders());
        assertEquals("No active orders available for batching", ex.getMessage());
        verify(batchRepository, never()).findFirstByAgentIdAndStatusOrderByCreatedAtDesc(anyLong(), any());
    }

    @Test
    void createBatchFromActiveOrders_existingActiveBatch_throwsBusinessException() {
        DeliveryAgent agent = agent(7L);
        Order order = order(1L, 12.9700, 77.5900, "ORD-1", Order.OrderStatus.READY_FOR_PICKUP);
        RiderDeliveryBatch existing = new RiderDeliveryBatch();
        existing.setId(99L);
        existing.setAgent(agent);
        existing.setStatus(RiderDeliveryBatch.BatchStatus.ACTIVE);

        when(deliveryService.getCurrentDeliveryAgent()).thenReturn(agent);
        when(orderRepository.findByDeliveryAgentIdAndStatusIn(eq(7L), anyList())).thenReturn(List.of(order));
        when(batchRepository.findFirstByAgentIdAndStatusOrderByCreatedAtDesc(7L, RiderDeliveryBatch.BatchStatus.ACTIVE))
                .thenReturn(Optional.of(existing));

        BusinessException ex = assertThrows(BusinessException.class,
                () -> dispatchService.createBatchFromActiveOrders());
        assertEquals("Agent already has an active delivery batch", ex.getMessage());
    }

    @Test
    void createBatchFromActiveOrders_withinBatchSize_createsBatchWithAllOrders() {
        DeliveryAgent agent = agent(7L);
        List<Order> orders = List.of(
                order(1L, 12.9700, 77.5900, "ORD-1", Order.OrderStatus.READY_FOR_PICKUP),
                order(2L, 12.9701, 77.5901, "ORD-2", Order.OrderStatus.READY_FOR_PICKUP));

        when(deliveryService.getCurrentDeliveryAgent()).thenReturn(agent);
        when(orderRepository.findByDeliveryAgentIdAndStatusIn(eq(7L), anyList())).thenReturn(orders);
        when(batchRepository.findFirstByAgentIdAndStatusOrderByCreatedAtDesc(7L, RiderDeliveryBatch.BatchStatus.ACTIVE))
                .thenReturn(Optional.empty());
        when(batchRepository.save(any(RiderDeliveryBatch.class))).thenAnswer(invocation -> {
            RiderDeliveryBatch batch = invocation.getArgument(0);
            if (batch.getId() == null) {
                batch.setId(100L);
            }
            return batch;
        });
        when(batchOrderRepository.save(any(RiderDeliveryBatchOrder.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
        when(batchOrderRepository.findByBatchIdOrderBySequenceNumberAsc(100L))
                .thenReturn(batchOrders(100L, orders));

        RiderBatchResponse response = dispatchService.createBatchFromActiveOrders();

        assertEquals(100L, response.getBatchId());
        assertEquals(7L, response.getAgentId());
        assertEquals("ACTIVE", response.getStatus());
        assertEquals(2, response.getOrders().size());
        assertEquals(1, response.getOrders().get(0).getSequenceNumber());
        assertEquals(2, response.getOrders().get(1).getSequenceNumber());
        verify(batchOrderRepository, times(2)).save(any(RiderDeliveryBatchOrder.class));
    }

    @Test
    void createBatchFromActiveOrders_moreThanBatchSize_selectsNearbyOrders() {
        DeliveryAgent agent = agent(7L);
        List<Order> orders = List.of(
                order(1L, 12.9700, 77.5900, "ORD-1", Order.OrderStatus.READY_FOR_PICKUP),
                order(2L, 12.9701, 77.5901, "ORD-2", Order.OrderStatus.READY_FOR_PICKUP),
                order(3L, 12.9702, 77.5902, "ORD-3", Order.OrderStatus.READY_FOR_PICKUP),
                order(4L, 12.9703, 77.5903, "ORD-4", Order.OrderStatus.READY_FOR_PICKUP),
                order(5L, 12.9704, 77.5904, "ORD-5", Order.OrderStatus.READY_FOR_PICKUP));

        when(deliveryService.getCurrentDeliveryAgent()).thenReturn(agent);
        when(orderRepository.findByDeliveryAgentIdAndStatusIn(eq(7L), anyList())).thenReturn(orders);
        when(batchRepository.findFirstByAgentIdAndStatusOrderByCreatedAtDesc(7L, RiderDeliveryBatch.BatchStatus.ACTIVE))
                .thenReturn(Optional.empty());
        when(batchRepository.save(any(RiderDeliveryBatch.class))).thenAnswer(invocation -> {
            RiderDeliveryBatch batch = invocation.getArgument(0);
            if (batch.getId() == null) {
                batch.setId(100L);
            }
            return batch;
        });
        when(batchOrderRepository.save(any(RiderDeliveryBatchOrder.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
        when(batchOrderRepository.findByBatchIdOrderBySequenceNumberAsc(100L))
                .thenReturn(batchOrders(100L, orders));

        RiderBatchResponse response = dispatchService.createBatchFromActiveOrders();

        assertEquals(100L, response.getBatchId());
        assertEquals("ACTIVE", response.getStatus());
        assertEquals(3, response.getOrders().size(), "only the nearest 3 orders within radius are batched");
        assertEquals(1, response.getOrders().get(0).getSequenceNumber());
        assertEquals(3, response.getOrders().get(2).getSequenceNumber());
        verify(batchOrderRepository, times(3)).save(any(RiderDeliveryBatchOrder.class));
    }

    @Test
    void createBatchFromActiveOrders_farOrdersAreExcluded() {
        DeliveryAgent agent = agent(7L);
        List<Order> orders = List.of(
                order(1L, 12.9700, 77.5900, "ORD-1", Order.OrderStatus.READY_FOR_PICKUP),
                order(2L, 12.9701, 77.5901, "ORD-2", Order.OrderStatus.READY_FOR_PICKUP),
                order(3L, 12.9702, 77.5902, "ORD-3", Order.OrderStatus.READY_FOR_PICKUP),
                order(4L, 13.5000, 78.5000, "ORD-FAR", Order.OrderStatus.READY_FOR_PICKUP));

        when(deliveryService.getCurrentDeliveryAgent()).thenReturn(agent);
        when(orderRepository.findByDeliveryAgentIdAndStatusIn(eq(7L), anyList())).thenReturn(orders);
        when(batchRepository.findFirstByAgentIdAndStatusOrderByCreatedAtDesc(7L, RiderDeliveryBatch.BatchStatus.ACTIVE))
                .thenReturn(Optional.empty());
        when(batchRepository.save(any(RiderDeliveryBatch.class))).thenAnswer(invocation -> {
            RiderDeliveryBatch batch = invocation.getArgument(0);
            if (batch.getId() == null) {
                batch.setId(100L);
            }
            return batch;
        });
        when(batchOrderRepository.save(any(RiderDeliveryBatchOrder.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
        when(batchOrderRepository.findByBatchIdOrderBySequenceNumberAsc(100L))
                .thenReturn(batchOrders(100L, orders));

        RiderBatchResponse response = dispatchService.createBatchFromActiveOrders();

        assertEquals(3, response.getOrders().size(), "the far order is outside the 2km radius and is dropped");
        assertTrue(response.getOrders().stream().noneMatch(e -> e.getOrderId() == 4L));
    }

    @Test
    void getActiveBatch_found_returnsResponse() {
        DeliveryAgent agent = agent(7L);
        RiderDeliveryBatch batch = new RiderDeliveryBatch();
        batch.setId(200L);
        batch.setAgent(agent);
        batch.setStatus(RiderDeliveryBatch.BatchStatus.ACTIVE);
        batch.setCreatedAt(java.time.LocalDateTime.of(2025, 1, 1, 10, 0));

        List<Order> orders = List.of(order(1L, 12.9700, 77.5900, "ORD-1", Order.OrderStatus.OUT_FOR_DELIVERY));
        List<RiderDeliveryBatchOrder> entries = batchOrders(200L, orders);

        when(deliveryService.getCurrentDeliveryAgent()).thenReturn(agent);
        when(batchRepository.findFirstByAgentIdAndStatusOrderByCreatedAtDesc(7L, RiderDeliveryBatch.BatchStatus.ACTIVE))
                .thenReturn(Optional.of(batch));
        when(batchOrderRepository.findByBatchIdOrderBySequenceNumberAsc(200L)).thenReturn(entries);
        when(orderRepository.findAllById(List.of(1L))).thenReturn(orders);

        RiderBatchResponse response = dispatchService.getActiveBatch();

        assertEquals(200L, response.getBatchId());
        assertEquals("ACTIVE", response.getStatus());
        assertEquals("2025-01-01T10:00", response.getCreatedAt());
        assertEquals(1, response.getOrders().size());
        assertEquals("ORD-1", response.getOrders().get(0).getOrderNumber());
    }

    @Test
    void getActiveBatch_noActiveBatch_throwsResourceNotFoundException() {
        DeliveryAgent agent = agent(7L);
        when(deliveryService.getCurrentDeliveryAgent()).thenReturn(agent);
        when(batchRepository.findFirstByAgentIdAndStatusOrderByCreatedAtDesc(7L, RiderDeliveryBatch.BatchStatus.ACTIVE))
                .thenReturn(Optional.empty());

        ResourceNotFoundException ex = assertThrows(ResourceNotFoundException.class,
                () -> dispatchService.getActiveBatch());
        assertEquals("No active delivery batch", ex.getMessage());
    }

    @Test
    void getActiveBatch_emptyEntries_returnsEmptyOrders() {
        DeliveryAgent agent = agent(7L);
        RiderDeliveryBatch batch = new RiderDeliveryBatch();
        batch.setId(200L);
        batch.setAgent(agent);
        batch.setStatus(RiderDeliveryBatch.BatchStatus.ACTIVE);

        when(deliveryService.getCurrentDeliveryAgent()).thenReturn(agent);
        when(batchRepository.findFirstByAgentIdAndStatusOrderByCreatedAtDesc(7L, RiderDeliveryBatch.BatchStatus.ACTIVE))
                .thenReturn(Optional.of(batch));
        when(batchOrderRepository.findByBatchIdOrderBySequenceNumberAsc(200L)).thenReturn(List.of());

        RiderBatchResponse response = dispatchService.getActiveBatch();

        assertEquals(200L, response.getBatchId());
        assertNotNull(response.getOrders());
        assertTrue(response.getOrders().isEmpty());
        verify(orderRepository, never()).findAllById(anyList());
    }

    @Test
    void completeBatch_found_marksCompletedAndReturnsResponse() {
        DeliveryAgent agent = agent(7L);
        RiderDeliveryBatch batch = new RiderDeliveryBatch();
        batch.setId(300L);
        batch.setAgent(agent);
        batch.setStatus(RiderDeliveryBatch.BatchStatus.ACTIVE);
        batch.setCreatedAt(java.time.LocalDateTime.of(2025, 1, 1, 10, 0));

        List<Order> orders = List.of(order(1L, 12.9700, 77.5900, "ORD-1", Order.OrderStatus.OUT_FOR_DELIVERY));
        List<RiderDeliveryBatchOrder> entries = batchOrders(300L, orders);

        when(batchRepository.findById(300L)).thenReturn(Optional.of(batch));
        when(batchRepository.save(any(RiderDeliveryBatch.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(batchOrderRepository.findByBatchIdOrderBySequenceNumberAsc(300L)).thenReturn(entries);
        when(orderRepository.findAllById(List.of(1L))).thenReturn(orders);

        RiderBatchResponse response = dispatchService.completeBatch(300L);

        assertEquals(RiderDeliveryBatch.BatchStatus.COMPLETED, batch.getStatus());
        assertNotNull(batch.getCompletedAt());
        assertEquals("COMPLETED", response.getStatus());
        assertEquals(1, response.getOrders().size());
        verify(batchRepository).save(batch);
    }

    @Test
    void completeBatch_notFound_throwsResourceNotFoundException() {
        when(batchRepository.findById(999L)).thenReturn(Optional.empty());

        ResourceNotFoundException ex = assertThrows(ResourceNotFoundException.class,
                () -> dispatchService.completeBatch(999L));
        assertEquals("Delivery batch not found", ex.getMessage());
        verify(batchRepository, never()).save(any(RiderDeliveryBatch.class));
    }

    private DeliveryAgent agent(Long id) {
        DeliveryAgent agent = new DeliveryAgent();
        agent.setId(id);
        agent.setAvailable(true);
        agent.setVerified(true);
        return agent;
    }

    private Order order(Long id, double lat, double lng, String orderNumber, Order.OrderStatus status) {
        Address address = new Address();
        address.setLatitude(lat);
        address.setLongitude(lng);

        Restaurant restaurant = new Restaurant();
        restaurant.setId(id + 100L);
        restaurant.setName("Restaurant " + id);
        restaurant.setAddress(address);

        Order order = new Order();
        order.setId(id);
        order.setOrderNumber(orderNumber);
        order.setRestaurant(restaurant);
        order.setStatus(status);
        return order;
    }

    private List<RiderDeliveryBatchOrder> batchOrders(Long batchId, List<Order> orders) {
        List<RiderDeliveryBatchOrder> result = new ArrayList<>();
        int sequence = 1;
        for (Order order : orders) {
            RiderDeliveryBatchOrder entry = new RiderDeliveryBatchOrder();
            entry.setBatchId(batchId);
            entry.setOrderId(order.getId());
            entry.setSequenceNumber(sequence++);
            result.add(entry);
        }
        return result;
    }
}