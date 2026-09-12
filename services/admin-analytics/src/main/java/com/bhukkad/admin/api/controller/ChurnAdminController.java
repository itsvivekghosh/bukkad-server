package com.bhukkad.admin.api.controller;

import com.bhukkad.admin.domain.entity.ChurnScore;
import com.bhukkad.admin.domain.service.ChurnService;
import com.bhukkad.common.dto.response.ApiResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDateTime;
import java.util.List;
import org.springframework.security.access.prepost.PreAuthorize;

/**
 * Admin retention dashboard (port of monolith {@code ChurnAdminController},
 * FEATURE #12): high-risk cohort review plus on-demand re-scoring of a single
 * customer. Responses keep the monolith JSON contract exactly: the
 * {@code ApiResponse} envelope plus a {@code userId}/{@code score}/
 * {@code riskLevel}/{@code factors}/{@code scoredAt}/{@code retentionActionTaken}
 * payload.
 */
@RestController
@RequestMapping("/api/v1/admin/churn")
@RequiredArgsConstructor
@PreAuthorize("hasRole('ADMIN')")
public class ChurnAdminController {

    private final ChurnService churnService;

    @GetMapping("/high-risk")
    public ResponseEntity<ApiResponse<List<ChurnScoreResponse>>> highRisk() {
        return ResponseEntity.ok(ApiResponse.success(churnService.highRiskCustomers().stream()
                .map(ChurnScoreResponse::from)
                .toList()));
    }

    @PostMapping("/rescore/{userId}")
    public ResponseEntity<ApiResponse<ChurnScoreResponse>> rescore(@PathVariable("userId") Long userId) {
        ChurnScore score = churnService.rescore(userId);
        return ResponseEntity.ok(ApiResponse.success(score == null ? null : ChurnScoreResponse.from(score)));
    }

    /**
     * Monolith {@code com.bhukkad.churn.ChurnScore} response shape. The service
     * stores scores on a 0–1 scale with no persisted risk level or retention
     * flag, so {@code score} is rescaled to the monolith's 0–100 contract,
     * {@code riskLevel} is derived with the monolith thresholds and
     * {@code retentionActionTaken} is always false (no backing column).
     */
    public record ChurnScoreResponse(Long id, Long userId, int score, String riskLevel,
                              String factors, LocalDateTime scoredAt, boolean retentionActionTaken) {

        /** Monolith retention threshold on the 0–100 scale ({@code app.churn.retention-threshold}). */
        private static final int HIGH_RISK_THRESHOLD = (int) Math.round(ChurnService.HIGH_RISK_SCORE * 100);

        /** Monolith MEDIUM band lower bound ({@code threshold / 2}, integer division). */
        private static final int MEDIUM_RISK_THRESHOLD = HIGH_RISK_THRESHOLD / 2;

        static ChurnScoreResponse from(ChurnScore score) {
            int scaled = (int) Math.round(score.getScore() * 100);
            return new ChurnScoreResponse(
                    score.getId(),
                    score.getCustomerId(),
                    scaled,
                    riskLevel(scaled),
                    score.getFeaturesJson(),
                    score.getComputedAt(),
                    false);
        }

        private static String riskLevel(int scaled) {
            if (scaled >= HIGH_RISK_THRESHOLD) {
                return "HIGH";
            }
            if (scaled >= MEDIUM_RISK_THRESHOLD) {
                return "MEDIUM";
            }
            return "LOW";
        }
    }
}
