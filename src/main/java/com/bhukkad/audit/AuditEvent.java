package com.bhukkad.audit;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import java.time.LocalDateTime;

/**
 * One entry in the append-only audit trail for sensitive operations.
 *
 * <p>Rows are write-once: nothing in the application updates or deletes an audit event, so the
 * table is a durable record of who did what, to which resource, and (via {@link #oldState} /
 * {@link #newState}) what changed. Rows are written by {@link AuditService} after the guarded
 * operation completes, and a failure to write one never fails the business operation itself.</p>
 */
@Entity
@Table(name = "audit_events", indexes = {
        @Index(name = "idx_audit_action_created", columnList = "action, created_at"),
        @Index(name = "idx_audit_resource", columnList = "resource_type, resource_id")
})
@Data
@NoArgsConstructor
@AllArgsConstructor
@EntityListeners(AuditingEntityListener.class)
public class AuditEvent {

    /** Surrogate key; assigned by the database on insert. */
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** Id of the acting user, or {@code null} for unauthenticated actors (e.g. failed logins). */
    @Column(name = "actor_id")
    private Long actorId;

    /** Role of the acting user at the time of the action, or {@code null} when unknown. */
    @Column(name = "actor_role", length = 30)
    private String actorRole;

    /** What happened, e.g. {@code REFUND}, {@code LOGIN_SUCCESS}, {@code USER_DEACTIVATED}. */
    @Column(nullable = false, length = 80)
    private String action;

    /** Kind of resource the action targeted, e.g. {@code PAYMENT}, {@code AUTH}, {@code USER}. */
    @Column(name = "resource_type", nullable = false, length = 80)
    private String resourceType;

    /** Identifier of the resource the action targeted (order/user/restaurant id, email, ...). */
    @Column(name = "resource_id", length = 100)
    private String resourceId;

    /** Snapshot of the state before the change, or {@code null} when not applicable. */
    @Column(name = "old_state", columnDefinition = "TEXT")
    private String oldState;

    /** Snapshot of the state after the change, or {@code null} when not applicable. */
    @Column(name = "new_state", columnDefinition = "TEXT")
    private String newState;

    /** Origin address of the request; sized for IPv6. */
    @Column(name = "ip_address", length = 45)
    private String ipAddress;

    /** Trace id from {@link com.bhukkad.logging.TraceContext} for request correlation. */
    @Column(name = "trace_id", length = 64)
    private String traceId;

    /** Request id from {@link com.bhukkad.logging.TraceContext} for request correlation. */
    @Column(name = "request_id", length = 64)
    private String requestId;

    /**
     * Insert timestamp, populated by JPA auditing and immutable thereafter.
     *
     * <p>This is the write-time of the audit event and is never set by application code.</p>
     */
    @CreatedDate
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;
}
