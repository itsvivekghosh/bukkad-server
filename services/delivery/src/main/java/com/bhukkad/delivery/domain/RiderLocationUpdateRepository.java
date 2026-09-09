package com.bhukkad.delivery.domain;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface RiderLocationUpdateRepository extends JpaRepository<RiderLocationUpdate, Long> {
    List<RiderLocationUpdate> findByAgentId(Long agentId);
}