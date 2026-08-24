package com.bhukkad.fraud;

import com.bhukkad.entity.FraudEvent;
import com.bhukkad.repository.FraudEventRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * Logistic-regression-style risk scoring for order creation (FEATURE #10).
 *
 * <p>Catches patterns the pure velocity rules miss: brand-new accounts placing
 * large first orders in the small hours, customers with a recent fraud history,
 * and burst velocity. Each feature is engineered from data available at scoring
 * time (no external calls), multiplied by a tunable coefficient, summed, and
 * squashed through a logistic curve to a 0–100 score:</p>
 *
 * <pre>score = 100 / (1 + e^-(bias + Σ weightᵢ × featureᵢ))</pre>
 *
 * <p><strong>Action policy</strong> (config-gated): score ≥ review threshold →
 * persist a review flag; score ≥ reject threshold and enforcement enabled →
 * reject via {@link FraudBlockedException}. Enforcement is off by default so the
 * score distribution can be observed before real rejections begin.</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class FraudRiskScoringService {

    static final String REVIEW_EVENT_TYPE = "ORDER_RISK_REVIEW";

    private final FraudScoringProperties properties;
    private final com.bhukkad.repository.FraudEventRepository fraudEventRepository;
    private final com.bhukkad.repository.OrderRepository orderRepository;
    private final com.bhukkad.repository.UserRepository userRepository;

    /**
     * Scores an incoming order.
     *
     * @param customerId      the placing customer (never null on this path)
     * @param orderTotalValue total payable amount of the new order
     * @return assessment with 0–100 score, level and human-readable reasons
     */
    public FraudRiskAssessment scoreOrder(Long customerId, double orderTotalValue) {
        if (!properties.isEnabled()) {
            return FraudRiskAssessment.noScore();
        }

        LocalDateTime now = LocalDateTime.now();
        List<String> reasons = new ArrayList<>();

        long velocity24h = safeCount(() ->
                orderRepository.countByCustomerIdAndCreatedAtAfter(customerId, now.minusHours(24)));
        if (velocity24h >= 5) {
            reasons.add("high_order_velocity_24h=" + velocity24h);
        }

        var user = userRepository.findById(customerId).orElse(null);
        AccountAge accountAge = assessAccountAge(user, now);
        if (accountAge.brandNew()) {
            reasons.add("account_age_lt_1d");
        } else if (accountAge.young()) {
            reasons.add("account_age_lt_7d");
        }

        long priorFraudEvents = safeCount(() ->
                fraudEventRepository.countByEventTypeAndCustomerIdAndCreatedAtAfter(
                        FraudEventTypes.ORDER_CREATE, customerId, now.minusDays(30)));
        if (priorFraudEvents > 0) {
            reasons.add("prior_fraud_events_30d=" + priorFraudEvents);
        }

        int hour = now.getHour();
        boolean lateNight = hour < 5;
        if (lateNight) {
            reasons.add("late_night_hour");
        }

        // Large first order: no completed purchase history and an outsized ticket.
        boolean largeFirstOrder = velocity24h == 0
                && orderTotalValue >= 3_000.0; // ~3× typical food-delivery ticket
        if (largeFirstOrder) {
            reasons.add("large_first_order");
        }

        double z = -3.0 // bias: keeps ordinary orders low-risk
                + properties.weight("velocity24h") * normalize(velocity24h, 10)
                + properties.weight("brandNewAccount") * (accountAge.brandNew() ? 1 : 0)
                + properties.weight("youngAccount") * (accountAge.young() ? 1 : 0)
                + properties.weight("priorFraudEvents") * normalize(priorFraudEvents, 3)
                + properties.weight("lateNightHour") * (lateNight ? 1 : 0)
                + properties.weight("largeFirstOrder") * (largeFirstOrder ? 1 : 0);

        int score = (int) Math.round(100.0 / (1.0 + Math.exp(-z)));
        return new FraudRiskAssessment(true, score, levelFor(score), List.copyOf(reasons));
    }

    /**
     * Applies the action policy for an assessment. Returns true when the caller
     * must abort (rejection), false otherwise.
     */
    public boolean shouldBlock(FraudRiskAssessment assessment, Long orderId) {
        if (!properties.isEnabled() || assessment == null || !assessment.scored()) {
            return false;
        }
        if (assessment.score() >= properties.getRejectThreshold()) {
            log.warn("FRAUD_SCORE_REJECT | orderId={} | score={} | reasons={}",
                    orderId, assessment.score(), assessment.reasons());
            return properties.isEnforcementEnabled();
        }
        if (assessment.score() >= properties.getReviewThreshold()) {
            log.info("FRAUD_SCORE_REVIEW | orderId={} | score={} | reasons={}",
                    orderId, assessment.score(), assessment.reasons());
        }
        return false;
    }

    /** Persists the manual-review flag as a fraud event (best-effort). */
    public void recordReviewFlag(Long customerId, Long orderId, FraudRiskAssessment assessment,
                                 String ip, String fingerprint) {
        try {
            FraudEvent event = new FraudEvent();
            event.setEventType(REVIEW_EVENT_TYPE);
            if (customerId != null) {
                // Lazy reference: only the FK is written; no customer select needed.
                var customerRef = new com.bhukkad.entity.Customer();
                customerRef.setId(customerId);
                event.setCustomer(customerRef);
            }
            event.setDetails(String.format("orderId=%d|score=%d|reasons=%s",
                    orderId != null ? orderId : -1L, assessment.score(),
                    String.join(",", assessment.reasons())));
            event.setIpAddress(ip);
            event.setDeviceFingerprint(fingerprint);
            fraudEventRepository.save(event);
        } catch (Exception ex) {
            log.warn("Failed to record fraud review flag: {}", ex.getMessage());
        }
    }

    private FraudRiskAssessment.Level levelFor(int score) {
        if (score >= properties.getRejectThreshold()) {
            return FraudRiskAssessment.Level.CRITICAL;
        }
        if (score >= properties.getReviewThreshold()) {
            return FraudRiskAssessment.Level.HIGH;
        }
        if (score >= properties.getReviewThreshold() / 2) {
            return FraudRiskAssessment.Level.MEDIUM;
        }
        return FraudRiskAssessment.Level.LOW;
    }

    /** Squashes a raw count into 0..1 assuming `cap` is "already extreme". */
    private static double normalize(long count, int cap) {
        return Math.min(1.0, count / (double) cap);
    }

    private long safeCount(java.util.function.Supplier<Long> query) {
        try {
            Long value = query.get();
            return value != null ? value : 0;
        } catch (Exception ex) {
            // Feature extraction must never break order placement.
            log.debug("Fraud feature lookup failed: {}", ex.getMessage());
            return 0;
        }
    }

    /** Categorises a customer account by age to drive the new/young-account signals. */
    private static AccountAge assessAccountAge(com.bhukkad.entity.User user, LocalDateTime now) {
        if (user == null || user.getCreatedAt() == null) {
            return new AccountAge(false, false);
        }
        long ageDays = java.time.temporal.ChronoUnit.DAYS.between(user.getCreatedAt().atZone(java.time.ZoneId.systemDefault()), now.atZone(java.time.ZoneId.systemDefault()));
        boolean brandNew = ageDays < 1;
        return new AccountAge(brandNew, ageDays < 7 && !brandNew);
    }

    private record AccountAge(boolean brandNew, boolean young) {
    }
}
