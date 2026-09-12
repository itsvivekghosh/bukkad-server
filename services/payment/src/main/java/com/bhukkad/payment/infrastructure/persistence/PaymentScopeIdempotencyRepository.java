package com.bhukkad.payment.infrastructure.persistence;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.Optional;

/**
 * Payment-service queries over the shared {@code idempotency_records} table for
 * the batch-contract scopes that are NOT members of the platform-lib
 * {@code IdempotencyScope} enum (this batch must not edit platform-lib):
 * {@code PAYMENT_CHARGE} (feature #1 idempotent charge) and
 * {@code DISPUTE_CREDIT} (ADR-001 dispute_resolved credit). The scope column is
 * {@code varchar(50)} and stores the enum-style string, so raw-string scopes are
 * safe; nothing in payment ever maps those rows back through the enum-typed
 * entity (all reads here are native).
 *
 * <p>The first-write-wins guarantee comes from the existing unique
 * {@code (scope, idempotency_key)} index — identical to the enum scopes.</p>
 */
public interface PaymentScopeIdempotencyRepository extends JpaRepository<com.bhukkad.common.idempotency.IdempotencyRecord, Long> {

    @Query(value = """
            SELECT response_payload FROM idempotency_records
            WHERE scope = :scope AND idempotency_key = :key
            """, nativeQuery = true)
    Optional<String> findResponsePayload(@Param("scope") String scope, @Param("key") String key);

    @Query(value = """
            SELECT status FROM idempotency_records
            WHERE scope = :scope AND idempotency_key = :key
            """, nativeQuery = true)
    Optional<String> findStatus(@Param("scope") String scope, @Param("key") String key);

    /** Claim/state flip for an existing row (claim renew, COMPLETED, FAILED). */
    @Modifying
    @Query(value = """
            UPDATE idempotency_records
            SET status = :status, response_payload = :payload, expires_at = :expiresAt
            WHERE scope = :scope AND idempotency_key = :key
            """, nativeQuery = true)
    int transition(@Param("scope") String scope, @Param("key") String key,
                   @Param("status") String status, @Param("payload") String payload,
                   @Param("expiresAt") LocalDateTime expiresAt);

    /** Releases a FAILED claim so the same key can retry (rows deleted, unique slot freed). */
    @Modifying
    @Query(value = """
            DELETE FROM idempotency_records
            WHERE scope = :scope AND idempotency_key = :key AND status = :status
            """, nativeQuery = true)
    int deleteByScopeKeyStatus(@Param("scope") String scope, @Param("key") String key,
                               @Param("status") String status);
}
