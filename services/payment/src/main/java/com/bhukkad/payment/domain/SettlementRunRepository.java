package com.bhukkad.payment.domain;

import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDate;

public interface SettlementRunRepository extends JpaRepository<SettlementRun, Long> {

    /** True when a settlement run (automated or manual) already exists for the date. */
    boolean existsByRunDate(LocalDate runDate);
}