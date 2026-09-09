package com.bhukkad.admin.service;

import com.bhukkad.admin.domain.AnalyticsExportTask;
import com.bhukkad.admin.domain.AnalyticsExportTaskRepository;
import com.bhukkad.admin.domain.FraudReviewAction;
import com.bhukkad.admin.domain.FraudReviewActionRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

/**
 * Fraud review queue + analytics export task lifecycle (Priority 8).
 */
@Service
@RequiredArgsConstructor
public class FraudAnalyticsService {

    private final FraudReviewActionRepository fraudRepository;
    private final AnalyticsExportTaskRepository exportRepository;

    @Transactional
    public FraudReviewAction enqueue(Long customerId, String rule, String severity) {
        FraudReviewAction action = new FraudReviewAction();
        action.setCustomerId(customerId);
        action.setRule(rule);
        action.setSeverity(severity);
        action.setStatus(FraudReviewAction.STATUS_PENDING);
        return fraudRepository.save(action);
    }

    @Transactional
    public FraudReviewAction review(Long actionId, String assignedTo, String notes) {
        FraudReviewAction action = fraudRepository.findById(actionId).orElseThrow();
        action.setStatus(FraudReviewAction.STATUS_REVIEWED);
        action.setAssignedTo(assignedTo);
        action.setNotes(notes);
        return fraudRepository.save(action);
    }

    @Transactional(readOnly = true)
    public List<FraudReviewAction> pendingFraud() {
        return fraudRepository.findByStatus(FraudReviewAction.STATUS_PENDING);
    }

    @Transactional
    public AnalyticsExportTask scheduleExport(String exportType, String filters) {
        AnalyticsExportTask task = new AnalyticsExportTask();
        task.setExportType(exportType);
        task.setStatus(AnalyticsExportTask.STATUS_PENDING);
        task.setFilters(filters);
        return exportRepository.save(task);
    }

    @Transactional
    public AnalyticsExportTask completeExport(Long taskId) {
        AnalyticsExportTask task = exportRepository.findById(taskId).orElseThrow();
        task.setStatus(AnalyticsExportTask.STATUS_COMPLETED);
        task.setFileUrl("/exports/" + UUID.randomUUID());
        return exportRepository.save(task);
    }
}