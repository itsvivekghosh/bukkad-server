package com.bhukkad.admin.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;
import java.time.LocalDateTime;

@Entity
@Table(name = "audit_events", indexes = {
        @Index(name = "idx_audit_actor", columnList = "actorType, actorId, createdAt"),
        @Index(name = "idx_audit_entity", columnList = "entityType, entityId")
})
@EntityListeners(AuditingEntityListener.class)
@Getter @Setter
public class AuditEvent {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(length = 30)
    private String actorType;
    private Long actorId;
    @Column(length = 30)
    private String actorRole;
    @Column(nullable = false, length = 100)
    private String action;
    @Column(length = 50)
    private String entityType;
    private Long entityId;
    @Column(length = 80)
    private String resourceType;
    @Column(length = 100)
    private String resourceId;
    @Column(columnDefinition = "TEXT")
    private String oldState;
    @Column(columnDefinition = "TEXT")
    private String newState;
    @Column(length = 45)
    private String ipAddress;
    @Column(length = 64)
    private String traceId;
    @Column(length = 64)
    private String requestId;
    private String details;
    @CreatedDate @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt;
}