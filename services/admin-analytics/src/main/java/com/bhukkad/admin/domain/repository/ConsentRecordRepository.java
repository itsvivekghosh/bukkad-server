package com.bhukkad.admin.domain.repository;
import com.bhukkad.admin.domain.entity.ConsentRecord;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface ConsentRecordRepository extends JpaRepository<ConsentRecord, Long> {

    Optional<ConsentRecord> findByUserIdAndPurpose(Long userId, String purpose);

    List<ConsentRecord> findByUserId(Long userId);
}
