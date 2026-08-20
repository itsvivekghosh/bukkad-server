package com.bhukkad.repository;

import com.bhukkad.entity.FraudReviewAction;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface FraudReviewActionRepository extends JpaRepository<FraudReviewAction, Long> {

    List<FraudReviewAction> findByStatusOrderByCreatedAtDesc(FraudReviewAction.FraudReviewStatus status);

    Optional<FraudReviewAction> findByFraudEventId(Long fraudEventId);

    boolean existsByFraudEventId(Long fraudEventId);

    long countByStatus(FraudReviewAction.FraudReviewStatus status);
}
