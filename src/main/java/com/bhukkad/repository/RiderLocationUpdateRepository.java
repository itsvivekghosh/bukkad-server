package com.bhukkad.repository;

import com.bhukkad.entity.RiderLocationUpdate;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface RiderLocationUpdateRepository extends JpaRepository<RiderLocationUpdate, Long> {
    Optional<RiderLocationUpdate> findFirstByOrderIdOrderByRecordedAtDesc(Long orderId);
}
