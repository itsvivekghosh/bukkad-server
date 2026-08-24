package com.bhukkad.audit;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

/**
 * Access to the append-only audit trail.
 *
 * <p>All queries are read-only; nothing in the application updates or deletes audit events.</p>
 */
@Repository
public interface AuditEventRepository extends JpaRepository<AuditEvent, Long> {

    /** Events targeting one resource, e.g. all audit rows for a given order. */
    List<AuditEvent> findByResourceTypeAndResourceId(String resourceType, String resourceId);

    /** Events performed by one actor, e.g. everything one admin did. */
    List<AuditEvent> findByActorId(Long actorId);

    /** Events of one action, e.g. all {@code LOGIN_FAILED} rows. */
    List<AuditEvent> findByAction(String action);
}
