package com.bhukkad.repository;

import com.bhukkad.entity.ZoneSurgeRule;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface ZoneSurgeRuleRepository extends JpaRepository<ZoneSurgeRule, Long> {
    List<ZoneSurgeRule> findByZoneIdAndIsActiveTrue(Long zoneId);
}
