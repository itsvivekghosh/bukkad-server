package com.bhukkad.admin.service;

import com.bhukkad.admin.domain.AuditEvent;
import com.bhukkad.admin.domain.AuditEventRepository;
import com.bhukkad.admin.domain.FraudEvent;
import com.bhukkad.admin.domain.FraudEventRepository;
import com.bhukkad.admin.domain.RestaurantOrderStat;
import com.bhukkad.admin.domain.RestaurantOrderStatRepository;
import lombok.RequiredArgsConstructor;
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

    private final AuditEventRepository auditRepository;
    private final FraudEventRepository fraudRepository;
    private final RestaurantOrderStatRepository statRepository;

    @Transactional(readOnly = true)
    public List<AuditEvent> auditTrail(String entityType, Long entityId) {
        return auditRepository.findByEntityTypeAndEntityId(entityType, entityId);
    }

    @Transactional(readOnly = true)
    public List<FraudEvent> fraudAlerts(String status) {
        return status != null ? fraudRepository.findByStatus(status) : fraudRepository.findAll();
    }

    @Transactional(readOnly = true)
    public List<RestaurantOrderStat> restaurantStats() {
        return statRepository.findAll();
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