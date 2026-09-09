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

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
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

    @Test
    void assign_picksActiveAgentAndPublishes() {
        when(assignmentRepository.findByOrderId(10L)).thenReturn(Optional.empty());
        when(agentRepository.findFirstByIsActiveTrue()).thenReturn(Optional.of(agent()));
        when(assignmentRepository.save(any(DeliveryAssignment.class))).thenAnswer(inv -> inv.getArgument(0));

        DeliveryAssignment assignment = service.assign(10L);

        assertThat(assignment.getStatus()).isEqualTo(DeliveryAssignment.STATUS_ASSIGNED);
        assertThat(assignment.getAgentId()).isEqualTo(9L);
        verify(eventPublisher).deliveryAssigned(10L, 9L);
    }

    @Test
    void assign_alreadyAssigned_throws() {
        when(assignmentRepository.findByOrderId(10L)).thenReturn(Optional.of(new DeliveryAssignment()));
        assertThatThrownBy(() -> service.assign(10L)).isInstanceOf(BusinessException.class)
                .hasMessageContaining("already assigned");
    }

    @Test
    void assign_noActiveAgent_throws() {
        when(assignmentRepository.findByOrderId(10L)).thenReturn(Optional.empty());
        when(agentRepository.findFirstByIsActiveTrue()).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.assign(10L)).isInstanceOf(BusinessException.class)
                .hasMessageContaining("No active delivery agent");
    }

    @Test
    void markDelivered_unknown_throws() {
        when(assignmentRepository.findByOrderId(99L)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.markDelivered(99L)).isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void markDelivered_doubleDelivery_throws() {
        DeliveryAssignment delivered = new DeliveryAssignment();
        delivered.setStatus(DeliveryAssignment.STATUS_DELIVERED);
        when(assignmentRepository.findByOrderId(10L)).thenReturn(Optional.of(delivered));
        assertThatThrownBy(() -> service.markDelivered(10L)).isInstanceOf(BusinessException.class)
                .hasMessageContaining("Already delivered");
    }
}
