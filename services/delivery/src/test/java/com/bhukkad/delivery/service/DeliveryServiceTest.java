package com.bhukkad.delivery.service;

import com.bhukkad.common.error.BusinessException;
import com.bhukkad.common.error.ResourceNotFoundException;
import com.bhukkad.delivery.domain.DeliveryAgent;
import com.bhukkad.delivery.domain.DeliveryAgentRepository;
import com.bhukkad.delivery.domain.DeliveryAssignment;
import com.bhukkad.delivery.domain.DeliveryAssignmentRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DeliveryServiceTest {

    @Mock private DeliveryAgentRepository agentRepository;
    @Mock private DeliveryAssignmentRepository assignmentRepository;
    @Mock private DeliveryEventPublisher eventPublisher;
    @InjectMocks private DeliveryService service;

    private DeliveryAgent agent() {
        DeliveryAgent a = new DeliveryAgent();
        a.setId(9L);
        a.setName("Rider 9");
        a.setIsActive(true);
        return a;
    }

    private DeliveryAssignment assignment(Long orderId, String status) {
        DeliveryAssignment a = new DeliveryAssignment();
        a.setOrderId(orderId);
        a.setAgentId(9L);
        a.setStatus(status);
        a.setAssignedAt(LocalDateTime.now());
        return a;
    }

    @Test
    void assign_picksActiveAgentInsertsAtomicallyAndPublishes() {
        when(assignmentRepository.findByOrderId(10L))
                .thenReturn(Optional.empty())
                .thenReturn(Optional.of(assignment(10L, DeliveryAssignment.STATUS_ASSIGNED)));
        when(agentRepository.findFirstByIsActiveTrue()).thenReturn(Optional.of(agent()));
        when(assignmentRepository.insertIfAbsent(eq(10L), eq(9L),
                eq(DeliveryAssignment.STATUS_ASSIGNED), any(LocalDateTime.class))).thenReturn(1);

        DeliveryAssignment assignment = service.assign(10L);

        assertThat(assignment.getStatus()).isEqualTo(DeliveryAssignment.STATUS_ASSIGNED);
        assertThat(assignment.getAgentId()).isEqualTo(9L);
        verify(eventPublisher).deliveryAssigned(10L, 9L);
    }

    @Test
    void assign_alreadyAssigned_throws() {
        when(assignmentRepository.findByOrderId(10L))
                .thenReturn(Optional.of(new DeliveryAssignment()));

        assertThatThrownBy(() -> service.assign(10L)).isInstanceOf(BusinessException.class)
                .hasMessageContaining("already assigned");
        verify(eventPublisher, never()).deliveryAssigned(anyLong(), anyLong());
    }

    @Test
    void assign_lostRace_throwsAlreadyAssignedAndDoesNotPublish() {
        // Pre-check passes (winner not yet committed), then the UNIQUE(order_id)
        // ON CONFLICT guard returns 0 → loser must behave like the pre-check path.
        when(assignmentRepository.findByOrderId(10L)).thenReturn(Optional.empty());
        when(agentRepository.findFirstByIsActiveTrue()).thenReturn(Optional.of(agent()));
        when(assignmentRepository.insertIfAbsent(eq(10L), eq(9L), anyString(), any()))
                .thenReturn(0);

        assertThatThrownBy(() -> service.assign(10L)).isInstanceOf(BusinessException.class)
                .hasMessageContaining("already assigned");
        verify(eventPublisher, never()).deliveryAssigned(anyLong(), anyLong());
    }

    @Test
    void assign_noActiveAgent_throws() {
        when(assignmentRepository.findByOrderId(10L)).thenReturn(Optional.empty());
        when(agentRepository.findFirstByIsActiveTrue()).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.assign(10L)).isInstanceOf(BusinessException.class)
                .hasMessageContaining("No active delivery agent");
        verify(assignmentRepository, never()).insertIfAbsent(anyLong(), anyLong(), anyString(), any());
    }

    @Test
    void markDelivered_unknown_throws() {
        when(assignmentRepository.markDeliveredIfOpen(eq(99L), anyString(), any())).thenReturn(0);
        when(assignmentRepository.findByOrderId(99L)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.markDelivered(99L)).isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void markDelivered_firstDelivery_transitionsAndPublishes() {
        when(assignmentRepository.markDeliveredIfOpen(eq(10L),
                eq(DeliveryAssignment.STATUS_DELIVERED), any(LocalDateTime.class))).thenReturn(1);
        when(assignmentRepository.findByOrderId(10L))
                .thenReturn(Optional.of(assignment(10L, DeliveryAssignment.STATUS_DELIVERED)));

        DeliveryAssignment result = service.markDelivered(10L);

        assertThat(result.getStatus()).isEqualTo(DeliveryAssignment.STATUS_DELIVERED);
        verify(eventPublisher).orderDelivered(10L, 9L);
    }

    @Test
    void markDelivered_secondDelivery_isIdempotentNoOp() {
        // 0 rows updated = the row was already DELIVERED: return it WITHOUT
        // a second OrderDelivered and WITHOUT throwing (caller-visible contract
        // change from B10; racing duplicates converge on this path too).
        when(assignmentRepository.markDeliveredIfOpen(eq(10L), anyString(), any())).thenReturn(0);
        when(assignmentRepository.findByOrderId(10L))
                .thenReturn(Optional.of(assignment(10L, DeliveryAssignment.STATUS_DELIVERED)));

        DeliveryAssignment result = service.markDelivered(10L);

        assertThat(result.getStatus()).isEqualTo(DeliveryAssignment.STATUS_DELIVERED);
        verify(eventPublisher, never()).orderDelivered(anyLong(), anyLong());
    }
}
