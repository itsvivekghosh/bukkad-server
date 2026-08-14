package com.bhukkad.idempotency;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface IdempotencyRecordRepository extends JpaRepository<IdempotencyRecord, Long> {

    Optional<IdempotencyRecord> findByScopeAndIdempotencyKey(
            IdempotencyRecord.IdempotencyScope scope,
            String idempotencyKey);
}
