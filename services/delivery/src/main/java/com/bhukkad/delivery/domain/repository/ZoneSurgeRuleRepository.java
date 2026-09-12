package com.bhukkad.delivery.domain.repository;
import com.bhukkad.delivery.domain.entity.ZoneSurgeRule;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface ZoneSurgeRuleRepository extends JpaRepository<ZoneSurgeRule, Long> {
    List<ZoneSurgeRule> findByZoneIdAndActiveTrue(Long zoneId);
}