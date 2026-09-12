package com.bhukkad.payment.domain.repository;

import org.springframework.data.jpa.repository.JpaRepository;

import com.bhukkad.payment.domain.entity.SettlementRun;
import java.time.LocalDate;

public interface SettlementRunRepository extends JpaRepository<SettlementRun, Long> {

    /** True when a settlement run (automated or manual) already exists for the date. */
    boolean existsByRunDate(LocalDate runDate);
}