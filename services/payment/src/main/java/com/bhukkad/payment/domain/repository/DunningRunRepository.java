package com.bhukkad.payment.domain.repository;

import org.springframework.data.jpa.repository.JpaRepository;

import com.bhukkad.payment.domain.entity.DunningRun;
import java.util.List;

public interface DunningRunRepository extends JpaRepository<DunningRun, Long> {
    List<DunningRun> findByStatus(String status);
}