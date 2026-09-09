package com.bhukkad.common.outbox;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Repository for the per-service {@code outbox_events} table. The claim query
 * uses {@code FOR UPDATE SKIP LOCKED} — syntax-identical on MySQL and
 * PostgreSQL — so horizontally scaled poller replicas each claim disjoint
 * batches without blocking (plan §5.2/§6.1).
 */
public interface OutboxEventRepository extends JpaRepository<OutboxEvent, Long> {

    @Query(value = """
            SELECT * FROM outbox_events
            WHERE status = :status
            ORDER BY created_at ASC
            LIMIT :limit
            FOR UPDATE SKIP LOCKED
            """, nativeQuery = true)
    List<OutboxEvent> findPendingForProcessing(@Param("status") String status, @Param("limit") int limit);

    @Query("SELECT e FROM OutboxEvent e WHERE e.status = :status " +
            "AND e.processingStartedAt IS NOT NULL AND e.processingStartedAt < :staleBefore")
    List<OutboxEvent> findStaleProcessing(@Param("status") OutboxEvent.OutboxStatus status,
                                          @Param("staleBefore") LocalDateTime staleBefore);

    long countByStatus(OutboxEvent.OutboxStatus status);
}
