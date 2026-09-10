package com.bhukkad.growth.repository;

import com.bhukkad.growth.entity.ReferralRecord;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface ReferralRecordRepository extends JpaRepository<ReferralRecord, Long> {

    Optional<ReferralRecord> findByReferrerIdAndReferredCustomerId(Long referrerId, Long referredCustomerId);

    boolean existsByReferredCustomerId(Long referredCustomerId);

    long countByReferrerId(Long referrerId);
}
