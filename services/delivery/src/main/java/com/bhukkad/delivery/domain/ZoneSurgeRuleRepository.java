package com.bhukkad.delivery.domain;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface ZoneSurgeRuleRepository extends JpaRepository<ZoneSurgeRule, Long> {
    List<ZoneSurgeRule> findByZoneIdAndActiveTrue(Long zoneId);
}