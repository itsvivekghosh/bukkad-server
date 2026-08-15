package com.bhukkad.repository;

import com.bhukkad.entity.SettlementRun;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface SettlementRunRepository extends JpaRepository<SettlementRun, Long> {
    List<SettlementRun> findTop10ByOrderByStartedAtDesc();
}
