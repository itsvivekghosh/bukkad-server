package com.bhukkad.identity.domain.repository;

import com.bhukkad.identity.domain.entity.ConsentRecord;

import com.bhukkad.identity.domain.entity.ConsentRecord;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface ConsentRecordRepository extends JpaRepository<ConsentRecord, Long> {
    Optional<ConsentRecord> findByUserIdAndPurpose(Long userId, String purpose);

    List<ConsentRecord> findByUserId(Long userId);
}