package com.bhukkad.churn;

import com.bhukkad.audit.AuditService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Weekly churn scoring and proactive retention (FEATURE #12).
 *
 * <p><strong>Signal engineering</strong> (0–100, higher = more likely to churn):</p>
 * <ul>
 *   <li>Recency decay — days since the last delivered order (up to 40 pts).</li>
 *   <li>Frequency decline — orders in the last 30 days vs the prior 30 (up to 25 pts).</li>
 *   <li>Delivery issues — cancelled-order share (up to 15 pts).</li>
 *   <li>Coupon dependency — share of orders needing a discount (up to 10 pts).</li>
 *   <li>One-and-done — a single order and nothing since (flat 10 pts).</li>
 * </ul>
 *
 * <p>Customers at or above {@code retentionThreshold} get one best-effort
 * "we miss you" outreach per scoring round; the flag on the row prevents repeat
 * spam. The admin dashboard exposes the high-risk cohort for manual review.</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ChurnPredictionService {

    private final ChurnProperties properties;
    private final ChurnQueryService queryService;
    private final ChurnScoreRepository churnScoreRepository;
    private final AuditService auditService;

    /**
     * Scores every active customer and upserts their churn score.
     * Weekly by default ({@code app.churn.scoring-cron}); locked so only one
     * instance sweeps at a time.
     */
    @Scheduled(cron = "${app.churn.scoring-cron:0 0 3 * * MON}")
    @SchedulerLock(name = "churn-weekly-scoring", lockAtMostFor = "PT2H")
    public void scoreCustomers() {
        if (!properties.isEnabled()) {
            return;
        }
        List<Long> customerIds = queryService.findScorableCustomerIds(properties.getBatchSize());
        int scored = 0;
        // Chunk to keep IN-clause sizes sane on large platforms.
        for (int i = 0; i < customerIds.size(); i += 200) {
            List<Long> chunk = customerIds.subList(i, Math.min(i + 200, customerIds.size()));
            scored += scoreChunk(chunk);
        }
        log.info("CHURN_SCORING_COMPLETED | customers={}", scored);
    }

    /** Scores a single customer on demand (used by admin tooling). */
    @Transactional
    public ChurnScore scoreCustomer(Long customerId) {
        return upsertScores(computeFactors(List.of(customerId)))
                .stream().findFirst().orElse(null);
    }

    /** High-risk cohort for the admin dashboard. */
    @Transactional(readOnly = true)
    public List<ChurnScore> highRiskCustomers() {
        return churnScoreRepository.findTop100ByRiskLevelOrderByScoreDesc(ChurnScore.RiskLevel.HIGH);
    }

    private int scoreChunk(List<Long> customerIds) {
        Map<Long, ChurnFactors> factors = computeFactors(customerIds);
        List<ChurnScore> saved = upsertScores(factors);
        dispatchRetention(saved);
        return saved.size();
    }

    private Map<Long, ChurnFactors> computeFactors(List<Long> customerIds) {
        Map<Long, ChurnFactors> result = new HashMap<>();
        for (Object[] row : queryService.customerOrderAggregates(customerIds)) {
            long customerId = ((Number) row[0]).longValue();
            LocalDateTime lastOrderAt = toDateTime(row[1]);
            long totalOrders = ((Number) row[2]).longValue();
            long recentOrders = ((Number) row[3]).longValue();
            long prevOrders = ((Number) row[4]).longValue();
            long cancelled = row[5] != null ? ((Number) row[5]).longValue() : 0;
            long couponOrders = row[6] != null ? ((Number) row[6]).longValue() : 0;
            result.put(customerId, new ChurnFactors(
                    lastOrderAt, totalOrders, recentOrders, prevOrders, cancelled, couponOrders));
        }
        return result;
    }

    private List<ChurnScore> upsertScores(Map<Long, ChurnFactors> factorsById) {
        List<ChurnScore> updated = new ArrayList<>();
        Map<Long, ChurnScore> existing = new HashMap<>();
        if (!factorsById.isEmpty()) {
            for (ChurnScore row : churnScoreRepository.findByUserIdIn(factorsById.keySet())) {
                existing.put(row.getUserId(), row);
            }
        }
        for (Map.Entry<Long, ChurnFactors> entry : factorsById.entrySet()) {
            Long customerId = entry.getKey();
            Scoring scoring = score(entry.getValue());

            ChurnScore entity = existing.getOrDefault(customerId, new ChurnScore());
            entity.setUserId(customerId);
            entity.setScore(scoring.score());
            entity.setRiskLevel(scoring.level());
            entity.setFactors(String.join("|", entry.getValue().describe()));
            // Clear the outreach flag whenever the customer drops out of the
            // action band, so a future relapse triggers exactly one campaign.
            if (!scoring.actionDue()) {
                entity.setRetentionActionTaken(false);
            }
            updated.add(entity);
        }
        List<ChurnScore> saved = churnScoreRepository.saveAll(updated);
        dispatchRetention(saved);
        return saved;
    }

    private void dispatchRetention(List<ChurnScore> scoredRows) {
        for (ChurnScore row : scoredRows) {
            boolean due = row.getRiskLevel() == ChurnScore.RiskLevel.HIGH && !row.isRetentionActionTaken();
            if (!due || !properties.isEnabled()) {
                continue;
            }
            try {
                // One outreach per scoring round: the row flag prevents repeats even
                // across weekly runs while the customer stays high-risk.
                auditService.recordEvent("RETENTION_OUTREACH", "USER", String.valueOf(row.getUserId()),
                        null, "we_miss_you_campaign", row.getUserId());
                log.info("CHURN_RETENTION_TRIGGERED | userId={} | score={}", row.getUserId(), row.getScore());
                row.setRetentionActionTaken(true);
                churnScoreRepository.save(row);
            } catch (Exception ex) {
                log.warn("Retention action failed | userId={} | error={}", row.getUserId(), ex.getMessage());
            }
        }
    }

    private record Scoring(int score, ChurnScore.RiskLevel level, boolean actionDue) {
    }

    private Scoring score(ChurnFactors f) {
        int score = 0;
        if (f.totalOrders() == 0) {
            // Never purchased → mild nudge priority, not a churn case yet.
            return new Scoring(20, ChurnScore.RiskLevel.LOW, false);
        }

        long daysInactive = f.lastOrderAt() == null
                ? properties.getRecencyDecayDays()
                : java.time.temporal.ChronoUnit.DAYS.between(
                f.lastOrderAt().atZone(java.time.ZoneId.systemDefault()),
                LocalDateTime.now().atZone(java.time.ZoneId.systemDefault()));

        // Recency: fully decayed after ~2× the configured window.
        score += Math.min(40, Math.round(daysInactive * (40.0 / (properties.getRecencyDecayDays() * 2))));

        // Frequency decline: recent vs previous 30-day windows.
        double declineRatio = f.prevOrders() == 0
                ? (f.recentOrders() == 0 ? 1 : 0)
                : 1.0 - ((double) f.recentOrders() / f.prevOrders());
        if (declineRatio > 0) {
            score += Math.min(25, Math.round(declineRatio * 25));
        }

        // Delivery issues.
        double cancelRate = (double) f.cancelled() / f.totalOrders();
        score += Math.min(15, Math.round(cancelRate * 30));

        // Coupon dependency.
        double couponShare = (double) f.couponOrders() / f.totalOrders();
        if (couponShare > 0.7) {
            score += Math.min(10, Math.round((couponShare - 0.7) * 33));
        }

        // One-and-done.
        if (f.totalOrders() == 1) {
            score += 10;
        }

        int bounded = (int) Math.min(100, score);
        ChurnScore.RiskLevel level =
                bounded >= properties.getRetentionThreshold() ? ChurnScore.RiskLevel.HIGH
                        : bounded >= properties.getRetentionThreshold() / 2 ? ChurnScore.RiskLevel.MEDIUM
                        : ChurnScore.RiskLevel.LOW;
        return new Scoring(bounded, level, level == ChurnScore.RiskLevel.HIGH);
    }

    private static LocalDateTime toDateTime(Object value) {
        return value instanceof LocalDateTime dt ? dt : null;
    }

    private record ChurnFactors(LocalDateTime lastOrderAt, long totalOrders, long recentOrders,
                                long prevOrders, long cancelled, long couponOrders) {

        List<String> describe() {
            List<String> parts = new ArrayList<>();
            parts.add("days_inactive=" + (lastOrderAt == null ? -1
                    : java.time.temporal.ChronoUnit.DAYS.between(lastOrderAt.atZone(java.time.ZoneId.systemDefault()), LocalDateTime.now().atZone(java.time.ZoneId.systemDefault()))));
            parts.add("total_orders=" + totalOrders);
            parts.add("recent_30d=" + recentOrders);
            parts.add("prev_30d=" + prevOrders);
            parts.add("cancelled=" + cancelled);
            parts.add("coupon_orders=" + couponOrders);
            return parts;
        }
    }
}
