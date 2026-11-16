package com.bhukkad.admin.analytics.domain.repository;
import com.bhukkad.admin.analytics.domain.entity.AuditEvent;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

import java.time.LocalDateTime;
import java.util.List;

public interface AuditEventRepository extends JpaRepository<AuditEvent, Long>, JpaSpecificationExecutor<AuditEvent> {
    List<AuditEvent> findByEntityTypeAndEntityId(String entityType, Long entityId);
    List<AuditEvent> findByActorId(Long actorId);
    List<AuditEvent> findByAction(String action);
    List<AuditEvent> findByResourceTypeAndResourceId(String resourceType, String resourceId);
    List<AuditEvent> findByCreatedAtBetween(LocalDateTime from, LocalDateTime to);
}