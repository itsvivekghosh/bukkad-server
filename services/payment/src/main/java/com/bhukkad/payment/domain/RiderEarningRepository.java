package com.bhukkad.payment.domain;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface RiderEarningRepository extends JpaRepository<RiderEarning, Long> {

    List<RiderEarning> findByAgentId(Long agentId);

    List<RiderEarning> findByAgentIdAndStatus(Long agentId, String status);
}
