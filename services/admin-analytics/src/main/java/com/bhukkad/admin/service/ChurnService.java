package com.bhukkad.admin.service;

import com.bhukkad.admin.domain.ChurnScore;
import com.bhukkad.admin.domain.ChurnScoreRepository;
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
}