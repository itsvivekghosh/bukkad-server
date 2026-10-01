package com.bhukkad.admin.analytics.domain.repository;
import com.bhukkad.admin.analytics.domain.entity.AuditEvent;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface AuditEventRepository extends JpaRepository<AuditEvent, Long> {
    List<AuditEvent> findByEntityTypeAndEntityId(String entityType, Long entityId);
    List<AuditEvent> findByActorId(Long actorId);
    List<AuditEvent> findByAction(String action);
    List<AuditEvent> findByResourceTypeAndResourceId(String resourceType, String resourceId);
}