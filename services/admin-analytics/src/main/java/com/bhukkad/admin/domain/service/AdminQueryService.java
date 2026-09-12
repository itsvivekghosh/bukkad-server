package com.bhukkad.admin.domain.service;

import com.bhukkad.admin.domain.entity.AuditEvent;
import com.bhukkad.admin.domain.repository.AuditEventRepository;
import com.bhukkad.admin.domain.entity.FraudEvent;
import com.bhukkad.admin.domain.repository.FraudEventRepository;
import com.bhukkad.admin.domain.entity.RestaurantOrderStat;
import com.bhukkad.admin.domain.repository.RestaurantOrderStatRepository;
import com.bhukkad.common.scan.AllowFullScan;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * Read-model service for admin dashboards. Populated by Kafka consumers from
 * other services (plan §6.2 {@code admin.readmodels.v1}); the REST endpoints
 * serve the materialised views.
 */
@Service
@RequiredArgsConstructor
public class AdminQueryService {

    /** PERF-3 cap for the admin read lists (page size bound). */
    public static final int LIST_PAGE_CAP = 200;

    private final AuditEventRepository auditRepository;
    private final FraudEventRepository fraudRepository;
    private final RestaurantOrderStatRepository statRepository;

    @Transactional(readOnly = true)
    public List<AuditEvent> auditTrail(String entityType, Long entityId) {
        return auditRepository.findByEntityTypeAndEntityId(entityType, entityId);
    }

    /**
     * PERF-3: fraud alerts were read whole-table. Bounded to the newest
     * {@value #LIST_PAGE_CAP} rows with ORDER BY created_at DESC in SQL; the
     * bare-list response shape is kept (additively capped — deeper history
     * needs a paging contract change and is not requested).
     */
    @Transactional(readOnly = true)
    @AllowFullScan(reason = "G-6 reviewed: SQL-side page of LIST_PAGE_CAP newest rows; status filter narrows further")
    public List<FraudEvent> fraudAlerts(String status) {
        var page = PageRequest.of(0, LIST_PAGE_CAP, Sort.by(Sort.Direction.DESC, "createdAt"));
        return status != null
                ? fraudRepository.findByStatusOrderByCreatedAtDesc(status, page)
                : fraudRepository.findAll(page).getContent();
    }

    /** PERF-3: bounded, deterministically ordered stats page (cap kept at {@value #LIST_PAGE_CAP}). */
    @Transactional(readOnly = true)
    @AllowFullScan(reason = "G-6 reviewed: SQL-side page of LIST_PAGE_CAP rows ordered by restaurantId")
    public List<RestaurantOrderStat> restaurantStats() {
        return statRepository.findAll(
                PageRequest.of(0, LIST_PAGE_CAP, Sort.by("restaurantId"))).getContent();
    }

    @Transactional
    public FraudEvent flagFraud(Long customerId, String rule, String severity) {
        FraudEvent event = new FraudEvent();
        event.setCustomerId(customerId);
        event.setRule(rule);
        event.setSeverity(severity);
        event.setStatus(FraudEvent.STATUS_REVIEW);
        return fraudRepository.save(event);
    }
}