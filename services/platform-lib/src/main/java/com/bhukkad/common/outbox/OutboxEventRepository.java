package com.bhukkad.common.outbox;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Repository for the per-service {@code outbox_events} table. The claim query
 * uses {@code FOR UPDATE SKIP LOCKED} — syntax-identical on MySQL and
 * PostgreSQL — so horizontally scaled relay replicas each claim disjoint
 * batches without blocking (plan §5.2/§6.1). Rows with a future
 * {@code next_attempt_at} (retry backoff from PERF-2) are skipped until due.
 *
 * <p>State transitions are expressed as <strong>batched</strong>
 * {@code UPDATE … WHERE id IN (:ids)} statements (PERF-2 / implementation
 * guide §6 PERF-3.5): one round-trip per batch instead of one save per row.</p>
 */
public interface OutboxEventRepository extends JpaRepository<OutboxEvent, Long>, OutboxEventRepositoryCustom {

    @Query(value = """
            SELECT * FROM outbox_events
            WHERE status = :status
              AND (next_attempt_at IS NULL OR next_attempt_at <= :now)
            ORDER BY created_at ASC
            LIMIT :limit
            FOR UPDATE SKIP LOCKED
            """, nativeQuery = true)
    List<OutboxEvent> findPendingForProcessing(@Param("status") String status,
                                               @Param("limit") int limit,
                                               @Param("now") LocalDateTime now);

    @Query(value = """
            SELECT * FROM outbox_events
            WHERE partition_id = :partitionId
              AND status = :status
              AND (next_attempt_at IS NULL OR next_attempt_at <= :now)
            ORDER BY created_at ASC
            LIMIT :limit
            FOR UPDATE SKIP LOCKED
            """, nativeQuery = true)
    List<OutboxEvent> findPendingForProcessingByPartition(@Param("partitionId") int partitionId,
                                                          @Param("status") String status,
                                                          @Param("limit") int limit,
                                                          @Param("now") LocalDateTime now);

    @Query("SELECT e FROM OutboxEvent e WHERE e.status = :status " +
            "AND e.processingStartedAt IS NOT NULL AND e.processingStartedAt < :staleBefore")
    List<OutboxEvent> findStaleProcessing(@Param("status") OutboxEvent.OutboxStatus status,
                                          @Param("staleBefore") LocalDateTime staleBefore);

    long countByStatus(OutboxEvent.OutboxStatus status);

    /** Claim flip: PENDING → PROCESSING for the locked batch (inside the claim tx). */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query(value = """
            UPDATE outbox_events SET status = 'PROCESSING', processing_started_at = :now,
                   next_attempt_at = NULL
            WHERE id IN (:ids)
            """, nativeQuery = true)
    int markProcessing(@Param("ids") List<Long> ids, @Param("now") LocalDateTime now);

    /** Publish ack: flip to PUBLISHED (inside the short state tx after the broker ack). */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query(value = """
            UPDATE outbox_events SET status = 'PUBLISHED', published_at = :now,
                   last_error = NULL, next_attempt_at = NULL, processing_started_at = NULL
            WHERE id IN (:ids)
            """, nativeQuery = true)
    int markPublished(@Param("ids") List<Long> ids, @Param("now") LocalDateTime now);

    /**
     * Failed publish below the retry budget: back to PENDING with bumped
     * {@code retry_count} and an exponential {@code next_attempt_at} so the
     * row is only re-claimed once due.
     */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query(value = """
            UPDATE outbox_events SET status = 'PENDING', retry_count = retry_count + 1,
                   next_attempt_at = :nextAttemptAt, processing_started_at = NULL,
                   last_error = :error
            WHERE id IN (:ids)
            """, nativeQuery = true)
    int markPendingRetry(@Param("ids") List<Long> ids,
                         @Param("nextAttemptAt") LocalDateTime nextAttemptAt,
                         @Param("error") String error);

    /**
     * Terminal flip after {@code maxRetries} exhaustion or a malformed payload:
     * the row is FAILED and has been copied to {@code dead_letter_events}.
     */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query(value = """
            UPDATE outbox_events SET status = 'FAILED', retry_count = retry_count + 1,
                   processing_started_at = NULL, next_attempt_at = NULL, last_error = :error
            WHERE id IN (:ids)
            """, nativeQuery = true)
    int markFailed(@Param("ids") List<Long> ids, @Param("error") String error);

    /**
     * Retention sweep: hard-delete PUBLISHED or FAILED rows older than the TTL
     * so the outbox table does not grow unbounded under extreme event traffic.
     * Bounded by {@code limit} so a single sweep never holds a long write lock.
     * Callers loop until zero rows are deleted (scheduled with fixed delay).
     * Uses a subquery because PostgreSQL does not support LIMIT directly in DELETE.
     */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query(value = """
            DELETE FROM outbox_events
            WHERE id IN (
                SELECT id FROM outbox_events
                WHERE status IN ('PUBLISHED', 'FAILED')
                  AND created_at < :olderThan
                LIMIT :limit
            )
            """, nativeQuery = true)
    int deleteOldTerminal(@Param("olderThan") LocalDateTime olderThan,
                          @Param("limit") int limit);
}
