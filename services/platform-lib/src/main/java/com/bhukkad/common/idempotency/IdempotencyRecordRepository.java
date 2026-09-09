package com.bhukkad.common.idempotency;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.Optional;

/**
 * Repository for the per-service {@code idempotency_records} table. The unique
 * {@code (scope, idempotency_key)} index makes concurrent duplicate inserts
 * fail-fast with a constraint violation, which the idempotency layer converts
 * into a {@code DuplicateRequestException}-style response.
 */
public interface IdempotencyRecordRepository extends JpaRepository<IdempotencyRecord, Long> {

    Optional<IdempotencyRecord> findByScopeAndIdempotencyKey(
            IdempotencyRecord.IdempotencyScope scope, String idempotencyKey);

    @Modifying
    int deleteByExpiresAtBefore(LocalDateTime expiresAt);

    /**
     * Batched expiry sweep for the platform cleanup scheduler (V-19): deletes
     * at most {@code limit} expired rows in one statement via the
     * {@code idx_idempotency_expires} index, so a big legacy backlog is bled
     * off in bounded transactions instead of one table-locking delete.
     */
    @Modifying
    @Query(value = """
            DELETE FROM idempotency_records
            WHERE id IN (
                SELECT id FROM idempotency_records
                WHERE expires_at < :expiresAtBefore
                ORDER BY expires_at
                LIMIT :limit
            )
            """, nativeQuery = true)
    int deleteBatch(@Param("expiresAtBefore") LocalDateTime expiresAtBefore,
                    @Param("limit") int limit);

    /** Targeted release (PERF-2/P-07): un-claim a dedupe row whose dispatch never started. */
    @Modifying
    int deleteByScopeAndIdempotencyKey(
            IdempotencyRecord.IdempotencyScope scope, String idempotencyKey);

    /** Inserts only when the (scope, key) pair does not exist (first-write-wins). */
    @Modifying
    @Query(value = """
            INSERT INTO idempotency_records
                (idempotency_key, scope, owner_id, status, response_payload, created_at, expires_at)
            SELECT :key, :scope, :ownerId, :status, :payload, localtimestamp(6), :expiresAt
            WHERE NOT EXISTS (
                SELECT 1 FROM idempotency_records
                WHERE scope = :scope AND idempotency_key = :key
            )
            """, nativeQuery = true)
    int insertIfAbsent(@Param("key") String key, @Param("scope") String scope,
                       @Param("ownerId") Long ownerId, @Param("status") String status,
                       @Param("payload") String payload, @Param("expiresAt") LocalDateTime expiresAt);
}
