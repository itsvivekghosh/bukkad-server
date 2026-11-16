package com.bhukkad.common.audit;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface AuditEventRepository extends JpaRepository<AuditEvent, UUID> {

    @Query("SELECT e FROM AuditEvent e ORDER BY e.createdAt DESC, e.id DESC")
    Optional<AuditEvent> findLatest();

    List<AuditEvent> findByAction(AuditEventAction action);

    List<AuditEvent> findByActorId(String actorId);

    List<AuditEvent> findByResourceTypeAndResourceId(String resourceType, String resourceId);

    List<AuditEvent> findByActionAndCreatedAtBetween(AuditEventAction action,
                                                     LocalDateTime from,
                                                     LocalDateTime to);

    List<AuditEvent> findByActorIdAndCreatedAtBetween(String actorId,
                                                      LocalDateTime from,
                                                      LocalDateTime to);

    List<AuditEvent> findByCreatedAtBetween(LocalDateTime from, LocalDateTime to);
}