package com.bhukkad.admin.domain.service;

import com.bhukkad.admin.domain.entity.ChurnScore;
import com.bhukkad.admin.domain.repository.ChurnScoreRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Churn scoring (Batch E depth). The scoring model is a simple deterministic
 * formula in the service; the persisted score drives admin dashboards.
 */
@Service
@RequiredArgsConstructor
public class ChurnService {

    public static final String MODEL_VERSION = "v1";

    /**
     * Monolith parity: {@code app.churn.retention-threshold} defaults to 65 on the
     * admin dashboard's 0–100 scale, i.e. 0.65 on this service's 0–1 stored scale.
     */
    public static final double HIGH_RISK_SCORE = 0.65;

    private final ChurnScoreRepository churnScoreRepository;

    @Transactional
    public ChurnScore compute(Long customerId, double score) {
        ChurnScore churn = new ChurnScore();
        churn.setCustomerId(customerId);
        churn.setScore(Math.max(0.0, Math.min(1.0, score)));
        churn.setModelVersion(MODEL_VERSION);
        churn.setComputedAt(LocalDateTime.now());
        return churnScoreRepository.save(churn);
    }

    @Transactional(readOnly = true)
    public List<ChurnScore> history(Long customerId) {
        return churnScoreRepository.findByCustomerId(customerId);
    }

    /**
     * High-risk cohort for the admin retention dashboard (port of monolith
     * {@code ChurnPredictionService#highRiskCustomers}): top 100 customers at or
     * above the retention threshold, best scores first.
     */
    @Transactional(readOnly = true)
    public List<ChurnScore> highRiskCustomers() {
        return churnScoreRepository.findTop100ByScoreGreaterThanEqualOrderByScoreDesc(HIGH_RISK_SCORE);
    }

    /**
     * On-demand re-scoring of a single customer (port of monolith
     * {@code ChurnPredictionService#scoreCustomer}) with the monolith's upsert
     * semantics (one row per customer). The order aggregates the monolith model
     * consumes live in the order service's database, so this refreshes the
     * customer's latest persisted score as a new scoring round; customers without
     * a stored score yield null, matching the monolith's empty-cohort response.
     */
    @Transactional
    public ChurnScore rescore(Long customerId) {
        return churnScoreRepository.findFirstByCustomerIdOrderByComputedAtDesc(customerId)
                .map(latest -> {
                    latest.setComputedAt(LocalDateTime.now());
                    return churnScoreRepository.save(latest);
                })
                .orElse(null);
    }
}