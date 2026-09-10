package com.bhukkad.growth.repository;

import com.bhukkad.common.idempotency.IdempotencyRecord;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.Optional;

/**
 * Growth-local access to the platform {@code idempotency_records} table for
 * the {@code LOYALTY_CREDIT} scope. The platform entity is reused (same
 * table, same {@code (scope, idempotency_key)} unique constraint); the
 * platform enum only lists the scopes it owns natively, so this interface
 * speaks the {@code LOYALTY_CREDIT} scope as a plain string.
 */
@Repository
public interface LoyaltyCreditIdempotencyRepository extends JpaRepository<IdempotencyRecord, Long> {

    String SCOPE = "LOYALTY_CREDIT";

    @Query(value = """
            SELECT * FROM idempotency_records
            WHERE scope = 'LOYALTY_CREDIT' AND idempotency_key = :key
            """, nativeQuery = true)
    Optional<IdempotencyRecord> findByKey(@Param("key") String key);

    /** First-write-wins guard: 0 rows = a concurrent duplicate already claimed the key. */
    @Modifying
    @Query(value = """
            INSERT INTO idempotency_records
                (idempotency_key, scope, owner_id, status, response_payload, created_at, expires_at)
            SELECT :key, 'LOYALTY_CREDIT', :ownerId, :status, :payload, localtimestamp(6), :expiresAt
            WHERE NOT EXISTS (
                SELECT 1 FROM idempotency_records
                WHERE scope = 'LOYALTY_CREDIT' AND idempotency_key = :key
            )
            """, nativeQuery = true)
    int insertIfAbsent(@Param("key") String key, @Param("ownerId") Long ownerId,
                       @Param("status") String status, @Param("payload") String payload,
                       @Param("expiresAt") LocalDateTime expiresAt);
}
