package com.bhukkad.admin.service;
import com.bhukkad.admin.domain.service.FraudAnalyticsService;

import com.bhukkad.admin.domain.entity.AnalyticsExportTask;
import com.bhukkad.admin.domain.repository.AnalyticsExportTaskRepository;
import com.bhukkad.admin.domain.entity.FraudReviewAction;
import com.bhukkad.admin.domain.repository.FraudReviewActionRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class FraudAnalyticsServiceTest {

    @Mock private FraudReviewActionRepository fraudRepository;
    @Mock private AnalyticsExportTaskRepository exportRepository;
    @InjectMocks private FraudAnalyticsService service;

    @Test
    void enqueue_createsPendingReviewAction() {
        when(fraudRepository.save(any(FraudReviewAction.class))).thenAnswer(inv -> inv.getArgument(0));

        FraudReviewAction action = service.enqueue(42L, "velocity", "HIGH");

        assertThat(action.getCustomerId()).isEqualTo(42L);
        assertThat(action.getRule()).isEqualTo("velocity");
        assertThat(action.getSeverity()).isEqualTo("HIGH");
        assertThat(action.getStatus()).isEqualTo(FraudReviewAction.STATUS_PENDING);
    }

    @Test
    void review_marksReviewedWithAssignee() {
        FraudReviewAction action = new FraudReviewAction();
        action.setId(1L);
        when(fraudRepository.findById(1L)).thenReturn(Optional.of(action));
        when(fraudRepository.save(any(FraudReviewAction.class))).thenAnswer(inv -> inv.getArgument(0));

        FraudReviewAction reviewed = service.review(1L, "ops-team", "looks legitimate");

        assertThat(reviewed.getStatus()).isEqualTo(FraudReviewAction.STATUS_REVIEWED);
        assertThat(reviewed.getAssignedTo()).isEqualTo("ops-team");
        assertThat(reviewed.getNotes()).isEqualTo("looks legitimate");
    }

    @Test
    void review_unknownAction_throws() {
        when(fraudRepository.findById(99L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.review(99L, "ops", null))
                .isInstanceOf(java.util.NoSuchElementException.class);
    }

    @Test
    void pendingFraud_returnsPendingActions() {
        when(fraudRepository.findByStatus(FraudReviewAction.STATUS_PENDING)).thenReturn(List.of());

        assertThat(service.pendingFraud()).isEmpty();
        verify(fraudRepository).findByStatus(FraudReviewAction.STATUS_PENDING);
    }

    @Test
    void scheduleExport_createsPendingTask() {
        when(exportRepository.save(any(AnalyticsExportTask.class))).thenAnswer(inv -> inv.getArgument(0));

        AnalyticsExportTask task = service.scheduleExport("orders", "status=COMPLETED");

        assertThat(task.getExportType()).isEqualTo("orders");
        assertThat(task.getFilters()).isEqualTo("status=COMPLETED");
        assertThat(task.getStatus()).isEqualTo(AnalyticsExportTask.STATUS_PENDING);
    }

    @Test
    void completeExport_marksCompletedWithFileUrl() {
        AnalyticsExportTask task = new AnalyticsExportTask();
        task.setId(2L);
        when(exportRepository.findById(2L)).thenReturn(Optional.of(task));
        when(exportRepository.save(any(AnalyticsExportTask.class))).thenAnswer(inv -> inv.getArgument(0));

        AnalyticsExportTask completed = service.completeExport(2L);

        assertThat(completed.getStatus()).isEqualTo(AnalyticsExportTask.STATUS_COMPLETED);
        assertThat(completed.getFileUrl()).startsWith("/exports/");
    }

    @Test
    void completeExport_unknownTask_throws() {
        when(exportRepository.findById(99L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.completeExport(99L))
                .isInstanceOf(java.util.NoSuchElementException.class);
    }
}
