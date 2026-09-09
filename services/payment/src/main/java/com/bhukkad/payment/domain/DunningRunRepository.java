package com.bhukkad.payment.domain;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface DunningRunRepository extends JpaRepository<DunningRun, Long> {
    List<DunningRun> findByStatus(String status);
}