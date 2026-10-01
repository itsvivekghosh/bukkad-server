package com.bhukkad.admin.analytics.domain.repository;
import com.bhukkad.admin.analytics.domain.entity.FraudReviewAction;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface FraudReviewActionRepository extends JpaRepository<FraudReviewAction, Long> {
    List<FraudReviewAction> findByStatus(String status);
}