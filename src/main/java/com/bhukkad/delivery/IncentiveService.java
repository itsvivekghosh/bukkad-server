package com.bhukkad.delivery;

import com.bhukkad.entity.RiderEarning;
import com.bhukkad.util.PriceCalculator;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.Map;

/**
 * Incentive engine for rider earnings. Rules are configured under
 * {@code app.incentives.rules} with keys: {@code peak-hour-multiplier},
 * {@code per-delivery-bonus}, {@code on-time-bonus} and {@code streak-bonus}.
 */
@Data
@Service
@ConfigurationProperties(prefix = "app.incentives")
public class IncentiveService {

    public static final String RULE_PEAK_HOUR_MULTIPLIER = "peak-hour-multiplier";
    public static final String RULE_PER_DELIVERY_BONUS = "per-delivery-bonus";
    public static final String RULE_ON_TIME_BONUS = "on-time-bonus";
    public static final String RULE_STREAK_BONUS = "streak-bonus";

    /** Deliveries in a single run that qualify for the streak bonus. */
    private static final int STREAK_THRESHOLD = 10;

    private Map<String, Double> rules = new HashMap<>();

    /**
     * Computes the incentive bonus for an earning and applies it to the entity.
     * Never throws; with no rules configured the bonus is zero.
     *
     * @param earning       earning to mutate (may be {@code null} for pure computation)
     * @param deliveryCount number of deliveries in the run
     * @param onTime        whether the deliveries were on time
     * @param isPeakHour    whether the run falls in a peak hour
     * @return the computed bonus amount and reason
     */
    public IncentiveResult applyIncentives(RiderEarning earning, int deliveryCount, boolean onTime, boolean isPeakHour) {
        if (rules == null || rules.isEmpty()) {
            if (earning != null) {
                earning.setBonusAmount(0.0);
                earning.setBonusReason(null);
            }
            return new IncentiveResult(0.0, null);
        }

        double bonus = 0.0;
        StringBuilder reason = new StringBuilder();

        double perDelivery = valueOf(RULE_PER_DELIVERY_BONUS);
        if (perDelivery > 0 && deliveryCount > 0) {
            double line = perDelivery * deliveryCount;
            bonus += line;
            reason.append("per-delivery x").append(deliveryCount)
                    .append("=").append(PriceCalculator.roundToTwoDecimals(line)).append("; ");
        }

        double onTimeBonus = valueOf(RULE_ON_TIME_BONUS);
        if (onTime && onTimeBonus > 0) {
            bonus += onTimeBonus;
            reason.append("on-time +").append(PriceCalculator.roundToTwoDecimals(onTimeBonus)).append("; ");
        }

        double streakBonus = valueOf(RULE_STREAK_BONUS);
        if (deliveryCount >= STREAK_THRESHOLD && streakBonus > 0) {
            bonus += streakBonus;
            reason.append("streak +").append(PriceCalculator.roundToTwoDecimals(streakBonus)).append("; ");
        }

        double multiplier = valueOf(RULE_PEAK_HOUR_MULTIPLIER);
        if (isPeakHour && multiplier > 0 && bonus > 0) {
            bonus *= multiplier;
            reason.append("peak x").append(PriceCalculator.roundToTwoDecimals(multiplier)).append("; ");
        }

        double rounded = PriceCalculator.roundToTwoDecimals(bonus);
        String reasonText = reason.isEmpty() ? null : reason.toString().trim();
        if (earning != null) {
            earning.setBonusAmount(rounded);
            earning.setBonusReason(reasonText);
        }
        return new IncentiveResult(rounded, reasonText);
    }

    private double valueOf(String key) {
        return rules.getOrDefault(key, 0.0);
    }

    public record IncentiveResult(double bonusAmount, String bonusReason) {
    }
}