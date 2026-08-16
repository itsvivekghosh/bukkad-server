package com.bhukkad.idempotency;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.Optional;

@Repository
public interface IdempotencyRecordRepository extends JpaRepository<IdempotencyRecord, Long> {

    Optional<IdempotencyRecord> findByScopeAndIdempotencyKey(
            IdempotencyRecord.IdempotencyScope scope,
            String idempotencyKey);

    @Modifying
    int deleteByExpiresAtBefore(LocalDateTime expiresAt);
}
