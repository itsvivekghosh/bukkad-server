package com.bhukkad.delivery.service;

import com.bhukkad.common.error.BusinessException;
import com.bhukkad.common.error.ResourceNotFoundException;
import com.bhukkad.delivery.config.DeliveryMatchingProperties;
import com.bhukkad.delivery.domain.DeliveryAgent;
import com.bhukkad.delivery.domain.DeliveryAgentRepository;
import com.bhukkad.delivery.domain.DeliveryAssignment;
import com.bhukkad.delivery.domain.DeliveryAssignmentRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DeliveryServiceTest {

    @Mock private DeliveryAgentRepository agentRepository;
    @Mock private DeliveryAssignmentRepository assignmentRepository;
    @Mock private DeliveryEventPublisher eventPublisher;
    @Spy private DeliveryMatchingProperties matchingProperties = new DeliveryMatchingProperties();
    @InjectMocks private DeliveryService service;

    private DeliveryAgent agent(long id) {
        DeliveryAgent a = new DeliveryAgent();
        a.setId(id);
        a.setName("Rider " + id);
        a.setIsActive(true);
        return a;
    }

    private DeliveryAssignment assignment(Long orderId, long agentId, String status) {
        DeliveryAssignment a = new DeliveryAssignment();
        a.setOrderId(orderId);
        a.setAgentId(agentId);
        a.setStatus(status);
        a.setAssignedAt(LocalDateTime.now());
        return a;
    }

    @Test
    void assign_picksActiveAgentInsertsAtomicallyAndPublishes() {
        when(assignmentRepository.findByOrderId(10L))
                .thenReturn(Optional.empty())
                .thenReturn(Optional.of(assignment(10L, 9L, DeliveryAssignment.STATUS_ASSIGNED)));
        when(agentRepository.findByIsActiveTrueOrderByIdAsc()).thenReturn(List.of(agent(9L)));
        when(agentRepository.incrementActiveLoadWithinCap(9L, 4)).thenReturn(1);
        when(assignmentRepository.insertIfAbsent(eq(10L), eq(9L),
                eq(DeliveryAssignment.STATUS_ASSIGNED), any(LocalDateTime.class))).thenReturn(1);

        DeliveryAssignment assignment = service.assign(10L);

        assertThat(assignment.getStatus()).isEqualTo(DeliveryAssignment.STATUS_ASSIGNED);
        assertThat(assignment.getAgentId()).isEqualTo(9L);
        verify(eventPublisher).deliveryAssigned(10L, 9L);
    }

    @Test
    void assign_candidateAtCapFallsThroughToNextAgent() {
        when(assignmentRepository.findByOrderId(10L))
                .thenReturn(Optional.empty())
                .thenReturn(Optional.of(assignment(10L, 8L, DeliveryAssignment.STATUS_ASSIGNED)));
        // First active agent is at cap (0 rows), the next one admits.
        when(agentRepository.findByIsActiveTrueOrderByIdAsc())
                .thenReturn(List.of(agent(9L), agent(8L)));
        when(agentRepository.incrementActiveLoadWithinCap(9L, 4)).thenReturn(0);
        when(agentRepository.incrementActiveLoadWithinCap(8L, 4)).thenReturn(1);
        when(assignmentRepository.insertIfAbsent(eq(10L), eq(8L),
                eq(DeliveryAssignment.STATUS_ASSIGNED), any(LocalDateTime.class))).thenReturn(1);

        DeliveryAssignment assignment = service.assign(10L);

        assertThat(assignment.getAgentId()).isEqualTo(8L);
        verify(eventPublisher).deliveryAssigned(10L, 8L);
    }

    @Test
    void assign_allCandidatesAtCap_throwsNoAgentAvailable() {
        when(assignmentRepository.findByOrderId(10L)).thenReturn(Optional.empty());
        when(agentRepository.findByIsActiveTrueOrderByIdAsc()).thenReturn(List.of(agent(9L)));
        when(agentRepository.incrementActiveLoadWithinCap(9L, 4)).thenReturn(0);

        assertThatThrownBy(() -> service.assign(10L))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("No active delivery agent");
        verify(assignmentRepository, never()).insertIfAbsent(anyLong(), anyLong(), anyString(), any());
        verify(eventPublisher, never()).deliveryAssigned(anyLong(), anyLong());
    }

    @Test
    void assign_capExhaustedButRacerCommitted_reportsAlreadyAssigned() {
        // Every candidate is at cap, but the pre-check passed because the
        // winning racer had not committed yet: after cap exhaustion the order
        // is re-checked and the honest answer is "already assigned".
        when(assignmentRepository.findByOrderId(10L))
                .thenReturn(Optional.empty())
                .thenReturn(Optional.of(assignment(10L, 9L, DeliveryAssignment.STATUS_ASSIGNED)));
        when(agentRepository.findByIsActiveTrueOrderByIdAsc()).thenReturn(List.of(agent(9L)));
        when(agentRepository.incrementActiveLoadWithinCap(9L, 4)).thenReturn(0);

        assertThatThrownBy(() -> service.assign(10L))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("already assigned");
        verify(assignmentRepository, never()).insertIfAbsent(anyLong(), anyLong(), anyString(), any());
        verify(eventPublisher, never()).deliveryAssigned(anyLong(), anyLong());
    }

    @Test
    void assign_geoMatchingEnabled_usesPositionedCandidatesFirst() {
        matchingProperties.getGeoMatching().setEnabled(true);
        when(assignmentRepository.findByOrderId(10L))
                .thenReturn(Optional.empty())
                .thenReturn(Optional.of(assignment(10L, 21L, DeliveryAssignment.STATUS_ASSIGNED)));
        when(agentRepository.findPositionedCandidates(isNull(), isNull(), any(LocalDateTime.class), eq(20)))
                .thenReturn(List.of(positioned(21L), positioned(22L)));
        when(agentRepository.incrementActiveLoadWithinCap(21L, 4)).thenReturn(1);
        when(assignmentRepository.insertIfAbsent(eq(10L), eq(21L), anyString(), any())).thenReturn(1);

        DeliveryAssignment assignment = service.assign(10L);

        assertThat(assignment.getAgentId()).isEqualTo(21L);
        // Positioned candidates short-circuit: the legacy pick is never consulted.
        verify(agentRepository, never()).findByIsActiveTrueOrderByIdAsc();
    }

    @Test
    void assign_geoMatchingEnabled_noFreshPositions_fallsBackToActivePick() {
        matchingProperties.getGeoMatching().setEnabled(true);
        when(assignmentRepository.findByOrderId(10L))
                .thenReturn(Optional.empty())
                .thenReturn(Optional.of(assignment(10L, 9L, DeliveryAssignment.STATUS_ASSIGNED)));
        when(agentRepository.findPositionedCandidates(isNull(), isNull(), any(LocalDateTime.class), eq(20)))
                .thenReturn(List.of());
        when(agentRepository.findByIsActiveTrueOrderByIdAsc()).thenReturn(List.of(agent(9L)));
        when(agentRepository.incrementActiveLoadWithinCap(9L, 4)).thenReturn(1);
        when(assignmentRepository.insertIfAbsent(eq(10L), eq(9L), anyString(), any())).thenReturn(1);

        assertThat(service.assign(10L).getAgentId()).isEqualTo(9L);
    }

    private DeliveryAgentRepository.RiderCandidate positioned(long id) {
        return new DeliveryAgentRepository.RiderCandidate() {
            @Override public Long getId() { return id; }
            @Override public Double getLatitude() { return 19.076; }
            @Override public Double getLongitude() { return 72.877; }
            @Override public LocalDateTime getRecordedAt() { return LocalDateTime.now(); }
            @Override public Double getDistanceKm() { return 1.0; }
        };
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
        when(agentRepository.findByIsActiveTrueOrderByIdAsc()).thenReturn(List.of(agent(9L)));
        when(agentRepository.incrementActiveLoadWithinCap(9L, 4)).thenReturn(1);
        when(assignmentRepository.insertIfAbsent(eq(10L), eq(9L), anyString(), any()))
                .thenReturn(0);

        assertThatThrownBy(() -> service.assign(10L)).isInstanceOf(BusinessException.class)
                .hasMessageContaining("already assigned");
        verify(eventPublisher, never()).deliveryAssigned(anyLong(), anyLong());
    }

    @Test
    void assign_noActiveAgent_throws() {
        when(assignmentRepository.findByOrderId(10L)).thenReturn(Optional.empty());
        when(agentRepository.findByIsActiveTrueOrderByIdAsc()).thenReturn(List.of());
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
    void markDelivered_firstDelivery_transitionsReleasesLoadAndPublishes() {
        when(assignmentRepository.markDeliveredIfOpen(eq(10L),
                eq(DeliveryAssignment.STATUS_DELIVERED), any(LocalDateTime.class))).thenReturn(1);
        when(assignmentRepository.findByOrderId(10L))
                .thenReturn(Optional.of(assignment(10L, 9L, DeliveryAssignment.STATUS_DELIVERED)));

        DeliveryAssignment result = service.markDelivered(10L);

        assertThat(result.getStatus()).isEqualTo(DeliveryAssignment.STATUS_DELIVERED);
        verify(agentRepository).decrementActiveLoad(9L);
        verify(eventPublisher).orderDelivered(10L, 9L);
    }

    @Test
    void markDelivered_secondDelivery_isIdempotentNoOp() {
        // 0 rows updated = the row was already DELIVERED: return it WITHOUT
        // a second OrderDelivered, WITHOUT a second load release and WITHOUT
        // throwing (caller-visible contract change from B10; racing duplicates
        // converge on this path too).
        when(assignmentRepository.markDeliveredIfOpen(eq(10L), anyString(), any())).thenReturn(0);
        when(assignmentRepository.findByOrderId(10L))
                .thenReturn(Optional.of(assignment(10L, 9L, DeliveryAssignment.STATUS_DELIVERED)));

        DeliveryAssignment result = service.markDelivered(10L);

        assertThat(result.getStatus()).isEqualTo(DeliveryAssignment.STATUS_DELIVERED);
        verify(agentRepository, never()).decrementActiveLoad(anyLong());
        verify(eventPublisher, never()).orderDelivered(anyLong(), anyLong());
    }
}
