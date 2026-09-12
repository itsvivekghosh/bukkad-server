package com.bhukkad.delivery.api.controller;

import com.bhukkad.common.error.BusinessException;
import com.bhukkad.common.error.UnauthorizedException;
import com.bhukkad.common.security.TokenPrincipal;
import com.bhukkad.delivery.domain.entity.DeliveryAgent;
import com.bhukkad.delivery.domain.repository.DeliveryAgentRepository;
import com.bhukkad.delivery.domain.entity.DeliveryAssignment;
import com.bhukkad.delivery.domain.repository.DeliveryAssignmentRepository;
import com.bhukkad.delivery.domain.entity.RiderDeliveryBatch;
import com.bhukkad.delivery.domain.repository.RiderDeliveryBatchRepository;
import com.bhukkad.delivery.domain.repository.RiderLocationUpdateRepository;
import com.bhukkad.delivery.domain.service.impl.RiderOpsService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.security.access.AccessDeniedException;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class RiderSelfControllerTest {

    @Mock private DeliveryAgentRepository agentRepository;
    @Mock private RiderOpsService riderOpsService;
    @Mock private RiderLocationUpdateRepository locationRepository;
    @Mock private DeliveryAssignmentRepository assignmentRepository;
    @Mock private RiderDeliveryBatchRepository batchRepository;
    @Mock private RiderSelfController.AgentProvisioner agentProvisioner;
    @InjectMocks private RiderSelfController controller;

    private static final long AGENT_ID = 10L;

    private DeliveryAgent agent;

    @BeforeEach
    void wire() {
        agent = new DeliveryAgent();
        agent.setId(AGENT_ID);
        agent.setName("Ravi");
        when(agentRepository.findById(AGENT_ID)).thenReturn(Optional.of(agent));
    }

    private static TokenPrincipal rider() {
        return new TokenPrincipal(AGENT_ID, "ravi@bhukkad.in", "DELIVERY_AGENT");
    }

    @Test
    void profile_existingAgent() {
        assertThat(controller.profile(rider()).getId()).isEqualTo(AGENT_ID);
        verify(agentProvisioner, never()).provision(anyLong(), any());
    }

    @Test
    void profile_missingAgent_isProvisionedLazily() {
        DeliveryAgent minted = new DeliveryAgent();
        minted.setId(77L);
        when(agentRepository.findById(77L)).thenReturn(Optional.empty());
        when(agentProvisioner.provision(77L, "new@rider.io")).thenReturn(minted);

        assertThat(controller.profile(
                new TokenPrincipal(77L, "new@rider.io", "DELIVERY_AGENT")).getId()).isEqualTo(77L);
    }

    @Test
    void profile_anonymousAndBadScope_rejected() {
        assertThatThrownBy(() -> controller.profile(null))
                .isInstanceOf(UnauthorizedException.class);
        assertThatThrownBy(() -> controller.profile(
                new TokenPrincipal(1L, "c@x.io", "CUSTOMER")))
                .isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> controller.profile(
                new TokenPrincipal(null, "x@y.z", "DELIVERY_AGENT")))
                .isInstanceOf(UnauthorizedException.class);
    }

    @Test
    void profile_adminScopeAllowed() {
        DeliveryAgent adminLinked = new DeliveryAgent();
        adminLinked.setId(1L);
        when(agentRepository.findById(1L)).thenReturn(java.util.Optional.empty());
        when(agentProvisioner.provision(1L, "ops@bhukkad.in")).thenReturn(adminLinked);

        assertThat(controller.profile(
                new TokenPrincipal(1L, "ops@bhukkad.in", "ADMIN")).getId()).isEqualTo(1L);
    }

    @Test
    void updateProfile_trimsFieldsAndIgnoresBlanks() {
        DeliveryAgent saved = new DeliveryAgent();
        when(agentRepository.save(any(DeliveryAgent.class))).thenReturn(saved);

        DeliveryAgent result = controller.updateProfile(rider(),
                new RiderSelfController.AgentProfileRequest("  Ravi K  ", "  999  ",
                        "BIKE", "KA01AB1234"));

        assertThat(agent.getName()).isEqualTo("Ravi K");
        assertThat(agent.getPhone()).isEqualTo("999");
        assertThat(agent.getVehicleType()).isEqualTo("BIKE");
        assertThat(agent.getVehicleNumber()).isEqualTo("KA01AB1234");
        assertThat(result).isSameAs(saved);
    }

    @Test
    void updateProfile_blankAndNullLimbsKeepExistingValues() {
        agent.setPhone("888");
        when(agentRepository.save(any(DeliveryAgent.class))).thenReturn(agent);

        controller.updateProfile(rider(), new RiderSelfController.AgentProfileRequest(
                "   ", "", null, null));

        assertThat(agent.getName()).isEqualTo("Ravi");
        assertThat(agent.getPhone()).isEqualTo("888");
        assertThat(agent.getVehicleType()).isNull();
    }

    @Test
    void updateProfile_nullBody_isNoOpUpdate() {
        when(agentRepository.save(any(DeliveryAgent.class))).thenReturn(agent);

        assertThat(controller.updateProfile(rider(), null)).isSameAs(agent);
    }

    @Test
    void toggleAvailability_nullDefaultsToActive() {
        agent.setIsActive(false);
        when(agentRepository.save(any(DeliveryAgent.class))).thenReturn(agent);

        assertThat(controller.toggleAvailability(rider(), null).getIsActive()).isTrue();
        assertThat(controller.toggleAvailability(rider(), false).getIsActive()).isFalse();
    }

    @Test
    void updateLocation_validCoordinates_delegates() {
        Map<String, Object> body = controller.updateLocation(rider(), 12.97, 77.59);

        verify(riderOpsService).reportLocation(AGENT_ID, 12.97, 77.59);
        assertThat(body).containsEntry("agentId", AGENT_ID).containsEntry("message", "Location updated");
    }

    @Test
    void updateLocation_missingOrOutOfRange_throws() {
        assertThatThrownBy(() -> controller.updateLocation(rider(), null, 77.0))
                .isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> controller.updateLocation(rider(), 91.0, 0.0))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("Invalid coordinates");
        assertThatThrownBy(() -> controller.updateLocation(rider(), 0.0, -181.0))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    void earningsSummary_aggregatesPeriodsFromService() {
        when(riderOpsService.getEarnings(AGENT_ID)).thenReturn(List.of(
                Map.of("period", "TODAY", "amount", 320.0, "deliveries", 5),
                Map.of("period", "WEEK", "amount", 2100.0, "deliveries", 31),
                Map.of("period", "TOTAL", "amount", 58000, "deliveries", 800),
                Map.of("period", "other")));

        Map<String, Object> summary = controller.earningsSummary(rider());

        assertThat(summary).containsEntry("today", 320.0)
                .containsEntry("thisWeek", 2100.0)
                .containsEntry("total", 58000.0)
                .containsEntry("deliveries", 836L);
    }

    @Test
    void earnings_pagesWithClampedSize() {
        List<Map<String, Object>> rows = List.of(
                Map.of("a", 1), Map.of("a", 2), Map.of("a", 3));
        when(riderOpsService.getEarnings(AGENT_ID)).thenReturn(rows);

        Map<String, Object> page = controller.earnings(rider(), 0, 2);

        assertThat(page).containsEntry("page", 0).containsEntry("size", 2)
                .containsEntry("hasNext", true);
        assertThat((List<?>) page.get("items")).hasSize(2);

        Map<String, Object> clamped = controller.earnings(rider(), -1, 500);
        assertThat(clamped).containsEntry("size", 100).containsEntry("hasNext", false);
    }

    @Test
    void earningsCursor_advancesFromNumericCursorAndToleratesGarbage() {
        List<Map<String, Object>> rows = List.of(Map.of("a", 1), Map.of("a", 2));
        when(riderOpsService.getEarnings(AGENT_ID)).thenReturn(rows);

        Map<String, Object> first = controller.earningsCursor(rider(), null, 1);
        assertThat(first).containsEntry("nextCursor", "1").containsEntry("hasNext", true);

        Map<String, Object> second = controller.earningsCursor(rider(), "1", 5);
        assertThat((List<?>) second.get("items")).hasSize(1);
        assertThat(second).containsEntry("nextCursor", "");

        Map<String, Object> garbage = controller.earningsCursor(rider(), "not-a-number", 5);
        assertThat((List<?>) garbage.get("items")).hasSize(2);
    }

    @Test
    void availableOrders_returnsEmptyQueue() {
        assertThat(controller.availableOrders(rider()))
                .containsEntry("count", 0);
    }

    @Test
    void activeDeliveries_filtersDeliveredRows() {
        DeliveryAssignment picked = assignment(1L, "ACCEPTED");
        DeliveryAssignment done = assignment(2L, "delivered");
        when(assignmentRepository.findByAgentId(AGENT_ID)).thenReturn(List.of(picked, done));

        Map<String, Object> body = controller.activeDeliveries(rider());

        assertThat(body).containsEntry("count", 1);
        assertThat((List<?>) body.get("items")).singleElement().isSameAs(picked);
    }

    @Test
    void deliveryHistory_pagesStatusFilteredRows() {
        when(assignmentRepository.findByAgentIdAndStatusIgnoreCase(AGENT_ID, "DELIVERED"))
                .thenReturn(List.of());

        assertThat(controller.deliveryHistory(rider(), 0, 10))
                .containsEntry("hasNext", false)
                .containsEntry("size", 10);
    }

    @Test
    void accept_existingUnclaimedAssignment_isAccepted() {
        DeliveryAssignment existing = assignment(7L, "READY_FOR_PICKUP");
        when(assignmentRepository.findByOrderId(7L)).thenReturn(Optional.of(existing));
        when(assignmentRepository.save(any(DeliveryAssignment.class)))
                .thenAnswer(inv -> inv.getArgument(0));

        Map<String, Object> body = controller.accept(rider(), 7L);

        assertThat(body).containsEntry("status", "ACCEPTED").containsEntry("orderId", 7L);
        assertThat(existing.getAcceptedAt()).isNotNull();
    }

    @Test
    void accept_missingAssignment_mintsFreshTrackedRow() {
        when(assignmentRepository.findByOrderId(8L)).thenReturn(Optional.empty());
        when(assignmentRepository.save(any(DeliveryAssignment.class)))
                .thenAnswer(inv -> inv.getArgument(0));

        controller.accept(rider(), 8L);

        var captor = org.mockito.ArgumentCaptor.forClass(DeliveryAssignment.class);
        verify(assignmentRepository).save(captor.capture());
        assertThat(captor.getValue().getStatus()).isEqualTo("ACCEPTED");
        assertThat(captor.getValue().getOrderId()).isEqualTo(8L);
    }

    @Test
    void accept_alreadyClaimedByOtherRider_isRejected() {
        DeliveryAssignment claimed = assignment(7L, "ACCEPTED");
        claimed.setAgentId(99L);
        when(assignmentRepository.findByOrderId(7L)).thenReturn(Optional.of(claimed));

        assertThatThrownBy(() -> controller.accept(rider(), 7L))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("another rider");
    }

    @Test
    void createBatch_validatesBodyAndDelegates() {
        assertThatThrownBy(() -> controller.createBatch(rider(), null))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("orderIds");
        RiderDeliveryBatch oversized = new RiderDeliveryBatch();
        assertThatThrownBy(() -> controller.createBatch(rider(),
                new RiderSelfController.BatchBody(List.of())))
                .isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> controller.createBatch(rider(),
                new RiderSelfController.BatchBody(java.util.stream.LongStream.rangeClosed(1, 11)
                        .boxed().toList())))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("at most 10");

        RiderDeliveryBatch batch = new RiderDeliveryBatch();
        batch.setId(5L);
        when(riderOpsService.createBatch(anyLong(), anyList())).thenReturn(batch);

        Map<String, Object> ok = controller.createBatch(rider(),
                new RiderSelfController.BatchBody(List.of(1L, 2L)));
        assertThat(ok).containsEntry("id", 5L).containsEntry("status", "OPEN");
        assertThat(oversized.getId()).isNull();
    }

    @Test
    void activeBatches_keepsOnlyOpenRuns() {
        RiderDeliveryBatch open = new RiderDeliveryBatch();
        open.setStatus("OPEN");
        RiderDeliveryBatch closed = new RiderDeliveryBatch();
        closed.setStatus("CLOSED");
        when(batchRepository.findByAgentId(AGENT_ID)).thenReturn(List.of(open, closed));

        assertThat(controller.activeBatches(rider())).containsEntry("count", 1);
    }

    private static DeliveryAssignment assignment(Long orderId, String status) {
        DeliveryAssignment a = new DeliveryAssignment();
        a.setOrderId(orderId);
        a.setStatus(status);
        return a;
    }
}
