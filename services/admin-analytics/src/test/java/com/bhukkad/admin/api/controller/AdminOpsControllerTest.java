package com.bhukkad.admin.api.controller;

import com.bhukkad.admin.domain.entity.FraudEvent;
import com.bhukkad.admin.domain.repository.ApiKeyRepository;
import com.bhukkad.admin.domain.repository.AuditEventRepository;
import com.bhukkad.admin.domain.repository.FraudEventRepository;
import com.bhukkad.common.error.ResourceNotFoundException;
import com.bhukkad.common.outbox.DeadLetterEvent;
import com.bhukkad.common.outbox.DeadLetterEventRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AdminOpsControllerTest {

    @Mock private FraudEventRepository fraudEventRepository;
    @Mock private ApiKeyRepository apiKeyRepository;
    @Mock private AuditEventRepository auditEventRepository;
    @Mock private DeadLetterEventRepository deadLetterRepository;

    @InjectMocks private AdminOpsController controller;

    private FraudEvent fraudEvent(Long id, String status) {
        FraudEvent event = new FraudEvent();
        event.setId(id);
        event.setCustomerId(3L);
        event.setRule("velocity");
        event.setSeverity("HIGH");
        event.setStatus(status);
        return event;
    }

    @Test
    void operationsDashboard_mixesLocalAggregates() {
        when(fraudEventRepository.findByStatus("PENDING"))
                .thenReturn(List.of(fraudEvent(1L, "PENDING"), fraudEvent(2L, "PENDING")));
        when(fraudEventRepository.count()).thenReturn(5L);
        when(auditEventRepository.count()).thenReturn(11L);
        when(apiKeyRepository.count()).thenReturn(2L);

        Map<String, Object> body = controller.operationsDashboard();

        assertThat(body).containsEntry("fraudPending", 2)
                .containsEntry("fraudTotal", 5L)
                .containsEntry("auditEvents", 11L)
                .containsEntry("apiKeys", 2L);
        assertThat(body).containsKey("generatedAt");
    }

    @Test
    void dashboard_reportsHonestZeroStateForMeshCounters() {
        when(fraudEventRepository.findByStatus("PENDING")).thenReturn(List.of());
        when(auditEventRepository.count()).thenReturn(1L);

        Map<String, Object> kpis = controller.dashboard();

        assertThat(kpis).containsEntry("totalUsers", 0)
                .containsEntry("totalOrders", 0)
                .containsEntry("totalRevenue", 0.0)
                .containsEntry("pendingFraudCases", 0);
    }

    @Test
    void analytics_aliasesStats() {
        when(fraudEventRepository.count()).thenReturn(4L);
        when(fraudEventRepository.findByStatus("PENDING")).thenReturn(List.of(fraudEvent(1L, "PENDING")));
        when(apiKeyRepository.count()).thenReturn(1L);
        when(auditEventRepository.count()).thenReturn(7L);

        Map<String, Object> body = controller.analytics();

        assertThat(body).containsEntry("totalFraudEvents", 4L)
                .containsEntry("pendingFraudEvents", 1)
                .containsEntry("totalApiKeys", 1L)
                .containsEntry("totalAuditEvents", 7L);
    }

    @Test
    void fraudDashboard_bucketsByStatus() {
        when(fraudEventRepository.count()).thenReturn(6L);
        when(fraudEventRepository.findByStatus("PENDING")).thenReturn(List.of(fraudEvent(1L, "PENDING")));
        when(fraudEventRepository.findByStatus("REVIEWED")).thenReturn(List.of(fraudEvent(2L, "REVIEWED"), fraudEvent(3L, "REVIEWED")));
        when(fraudEventRepository.findByStatus("DISMISSED")).thenReturn(List.of());

        Map<String, Object> body = controller.fraudDashboard();

        assertThat(body).containsEntry("total", 6L).containsEntry("pending", 1)
                .containsEntry("reviewed", 2).containsEntry("dismissed", 0);
    }

    @Test
    void fraudEventsAlias_capsToNewest100() {
        when(fraudEventRepository.findAll(any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(fraudEvent(1L, "PENDING"))));

        assertThat(controller.fraudEventsAlias()).hasSize(1);
        verify(fraudEventRepository).findAll(any(Pageable.class));
    }

    @Test
    void fraudEvents_clampsPaging() {
        when(fraudEventRepository.findAll(any(Pageable.class))).thenReturn(new PageImpl<>(List.of()));

        controller.fraudEvents(-5, 999);

        var captor = org.mockito.ArgumentCaptor.forClass(Pageable.class);
        verify(fraudEventRepository).findAll(captor.capture());
        assertThat(captor.getValue().getPageNumber()).isZero();
        assertThat(captor.getValue().getPageSize()).isEqualTo(100);
    }

    @Test
    void fraudEvents_sizeFloorIsOne() {
        when(fraudEventRepository.findAll(any(Pageable.class))).thenReturn(new PageImpl<>(List.of()));

        controller.fraudEvents(2, 0);

        var captor = org.mockito.ArgumentCaptor.forClass(Pageable.class);
        verify(fraudEventRepository).findAll(captor.capture());
        assertThat(captor.getValue().getPageSize()).isEqualTo(1);
        assertThat(captor.getValue().getPageNumber()).isEqualTo(2);
    }

    @Test
    void fraudReviewQueue_returnsPending() {
        when(fraudEventRepository.findByStatus("PENDING")).thenReturn(List.of(fraudEvent(1L, "PENDING")));

        assertThat(controller.fraudReviewQueue()).hasSize(1);
    }

    @Test
    void fraudReviewAction_dismissMapsToDismissedStatus() {
        FraudEvent event = fraudEvent(9L, "PENDING");
        when(fraudEventRepository.findById(9L)).thenReturn(Optional.of(event));
        when(fraudEventRepository.save(event)).thenReturn(event);

        FraudEvent result = controller.fraudReviewAction(9L, "DISMISS");

        assertThat(result.getStatus()).isEqualTo("DISMISSED");
        assertThat(result.getDetails()).endsWith("reviewed=DISMISSED");
    }

    @Test
    void fraudReviewAction_reviewMapsToReviewedAndAppendsToExistingDetails() {
        FraudEvent event = fraudEvent(9L, "PENDING");
        event.setDetails("ip velocity");
        when(fraudEventRepository.findById(9L)).thenReturn(Optional.of(event));
        when(fraudEventRepository.save(event)).thenReturn(event);

        FraudEvent result = controller.fraudReviewAction(9L, "review");

        assertThat(result.getStatus()).isEqualTo("REVIEWED");
        assertThat(result.getDetails()).isEqualTo("ip velocity | reviewed=REVIEWED");
    }

    @Test
    void fraudReviewAction_unknownAction_upperCasesThrough() {
        FraudEvent event = fraudEvent(9L, "PENDING");
        when(fraudEventRepository.findById(9L)).thenReturn(Optional.of(event));
        when(fraudEventRepository.save(event)).thenReturn(event);

        assertThat(controller.fraudReviewAction(9L, "block").getStatus()).isEqualTo("BLOCK");
    }

    @Test
    void fraudReviewAction_missingEvent_throwsNotFound() {
        when(fraudEventRepository.findById(404L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> controller.fraudReviewAction(404L, "REVIEW"))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessageContaining("Fraud event not found");
    }

    @Test
    void revenue_isHonestZeroStateAndClampsDays() {
        assertThat(controller.revenue(0)).containsEntry("days", 1)
                .containsEntry("grossRevenue", 0.0).containsEntry("orderCount", 0);
        assertThat(controller.revenue(30)).containsEntry("days", 30);
    }

    @Test
    void deadLetters_projectsRowsAndClampsPage() {
        DeadLetterEvent dlq = new DeadLetterEvent();
        dlq.setId(1L);
        dlq.setEventType("order.created");
        dlq.setAggregateType("ORDER");
        dlq.setAggregateId(10L);
        dlq.setPayload("{}");
        dlq.setLastError("boom");
        dlq.setRetryCount(3);
        dlq.setSource("order");
        dlq.setStatus(DeadLetterEvent.DlqStatus.PENDING);
        dlq.setCreatedAt(LocalDateTime.now());
        when(deadLetterRepository.findAllOrderByCreatedAtDesc(any(Pageable.class))).thenReturn(List.of(dlq));

        List<Map<String, Object>> rows = controller.deadLetters(-1, 500);

        assertThat(rows).hasSize(1);
        assertThat(rows.get(0)).containsEntry("eventType", "order.created")
                .containsEntry("failureReason", "boom")
                .containsEntry("retries", 3)
                .containsEntry("status", DeadLetterEvent.DlqStatus.PENDING);
        var captor = org.mockito.ArgumentCaptor.forClass(Pageable.class);
        verify(deadLetterRepository).findAllOrderByCreatedAtDesc(captor.capture());
        assertThat(captor.getValue().getPageSize()).isEqualTo(100);
    }

    @Test
    void dlqPendingCount_returnsCount() {
        when(deadLetterRepository.countByStatus(DeadLetterEvent.DlqStatus.PENDING)).thenReturn(4L);

        assertThat(controller.dlqPendingCount()).containsEntry("pending", 4L);
    }

    @Test
    void triggerSettlementRun_isAccepted() {
        ResponseEntity<Map<String, Object>> response = controller.triggerSettlementRun();

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        assertThat(response.getBody()).containsEntry("status", "QUEUED");
        assertThat((String) response.getBody().get("runId")).startsWith("run-");
    }

    @Test
    void apiKeyCreateRequestRecord_works() {
        var request = new AdminOpsController.ApiKeyCreateRequest("ci-bot");
        assertThat(request.name()).isEqualTo("ci-bot");
        assertThat(request).isEqualTo(new AdminOpsController.ApiKeyCreateRequest("ci-bot"));
    }
}
