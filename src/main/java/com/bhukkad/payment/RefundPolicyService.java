package com.bhukkad.payment;

import com.bhukkad.entity.Order;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Cancellation-policy-driven refund eligibility.
 *
 * <p>Configuration lives under {@code app.refund}:
 * <pre>
 * app:
 *   refund:
 *     enabled: true
 *     policies:
 *       CUSTOMER_CANCELLED:
 *         percent: 100
 *         target: WALLET
 *         maxMinutesAfterOrder: 30
 *       RESTAURANT_CANCELLED:
 *         percent: 100
 *         target: GATEWAY
 *         maxMinutesAfterOrder: 1440
 *       DELIVERY_FAILED:
 *         percent: 100
 *         target: GATEWAY
 *         maxMinutesAfterOrder: 1440
 * </pre>
 * {@code percent} is a percentage (0-100), {@code target} is WALLET or GATEWAY, and
 * {@code maxMinutesAfterOrder} bounds the window after order creation in which the
 * policy applies.
 */
@Service
@Data
@ConfigurationProperties(prefix = "app.refund")
public class RefundPolicyService {

    /** Refund target: credit the customer wallet directly. */
    public static final String TARGET_WALLET = "WALLET";

    /** Refund target: reverse the money through the payment gateway. */
    public static final String TARGET_GATEWAY = "GATEWAY";

    /** Per-cancellation-reason refund policy. */
    public record RefundPolicy(Double percent, String target, int maxMinutesAfterOrder) {}

    private boolean enabled = false;
    private Map<String, RefundPolicy> policies = new HashMap<>();

    /**
     * Resolves the applicable refund policy for a cancelled order, if any.
     * Defensive: unknown reasons, a disabled feature, unverifiable order
     * timestamps, non-positive percentages and expired windows all yield
     * {@link Optional#empty()} — never a throw.
     */
    public Optional<RefundPolicy> computeRefund(Order order, String cancellationReason) {
        if (!enabled || order == null || !StringUtils.hasText(cancellationReason)) {
            return Optional.empty();
        }
        RefundPolicy policy = policies.get(cancellationReason);
        if (policy == null || policy.percent() == null || policy.percent() <= 0
                || policy.maxMinutesAfterOrder() <= 0) {
            return Optional.empty();
        }
        if (order.getCreatedAt() == null) {
            return Optional.empty();
        }
        if (LocalDateTime.now().isAfter(order.getCreatedAt().plusMinutes(policy.maxMinutesAfterOrder()))) {
            return Optional.empty();
        }
        return Optional.of(policy);
    }
}
