package com.bhukkad.outbox;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface OutboxEventRepository extends JpaRepository<OutboxEvent, Long> {

    /**
     * Claims a batch of pending events for processing using
     * {@code SELECT ... FOR UPDATE SKIP LOCKED} (MySQL 8.0+ / MariaDB 10.6+).
     *
     * <p>In a horizontally scaled deployment every replica runs the outbox
     * poller. Without a row lock two replicas can select the same {@code
     * PENDING} batch and both publish the same events. {@code FOR UPDATE}
     * takes a pessimistic write lock on the selected rows for the duration of
     * the caller's transaction; {@code SKIP LOCKED} makes a concurrent poller
     * skip rows that an in-flight sweep has already locked instead of blocking
     * on them, so exactly one replica processes each event.
     *
     * @param status the {@code OutboxEvent.OutboxStatus} name ({@code PENDING})
     * @param limit  the maximum number of rows to claim (batch size)
     */
    @Query(value = """
            SELECT * FROM outbox_events
            WHERE status = :status
            ORDER BY created_at ASC
            LIMIT :limit
            FOR UPDATE SKIP LOCKED
            """, nativeQuery = true)
    List<OutboxEvent> findPendingForProcessing(@Param("status") String status, @Param("limit") int limit);
}
