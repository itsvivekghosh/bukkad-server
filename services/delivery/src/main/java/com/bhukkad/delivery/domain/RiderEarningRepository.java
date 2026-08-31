package com.bhukkad.delivery.domain;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface RiderEarningRepository extends JpaRepository<RiderEarning, Long> {
    List<RiderEarning> findByAgentId(Long agentId);
}