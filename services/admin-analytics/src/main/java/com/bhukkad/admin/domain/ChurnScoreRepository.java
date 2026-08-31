package com.bhukkad.admin.domain;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface ChurnScoreRepository extends JpaRepository<ChurnScore, Long> {
    List<ChurnScore> findByCustomerId(Long customerId);
}