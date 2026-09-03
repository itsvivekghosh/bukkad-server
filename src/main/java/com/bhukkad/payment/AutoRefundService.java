package com.bhukkad.payment;

import com.bhukkad.entity.Order;
import com.bhukkad.entity.Payment;
import com.bhukkad.entity.WalletTransaction;
import com.bhukkad.repository.OrderRepository;
import com.bhukkad.common.error.ResourceNotFoundException;
import com.bhukkad.repository.PaymentRepository;
import com.bhukkad.service.PaymentService;
import com.bhukkad.util.PriceCalculator;
import com.bhukkad.wallet.WalletService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Cancellation-policy-driven automatic refund orchestration.
 *
 * <p>Resolves the refund policy for a cancelled order and executes it against the
 * customer wallet (direct credit) or the payment gateway (full refund) depending
 * on the policy target. All execution is defensive: failures are logged and
 * swallowed, never rethrown.
 *
 * <p>Double-refund protection is two-layered. The {@code REFUNDED} payment status
 * is the database truth, and a Redis SETNX claim (key
 * {@code bhukkad:autorefund:{orderId}:{reason}}) provides cross-instance
 * idempotency so two replicas handling the same cancellation never both execute
 * the money path. A local in-memory cache mirrors the Redis claim for the hot
 * retry path; Redis is the source of truth for cluster consistency.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AutoRefundService {

    private final RefundPolicyService refundPolicyService;
    private final PaymentService paymentService;
    private final WalletService walletService;
    private final OrderRepository orderRepository;
    private final PaymentRepository paymentRepository;
    private final StringRedisTemplate stringRedisTemplate;

    /** TTL for auto-refund idempotency keys: 24 hours should cover any retry window. */
    private static final Duration CLAIM_TTL = Duration.ofHours(24);

    /** Redis key prefix for auto-refund idempotency claims. */
    static final String REFUND_CLAIM_PREFIX = "bhukkad:autorefund:";

    /**
     * Local cache of idempotency claims. Written through on every outcome, used
     * as a fast check before Redis and as fallback when Redis is unreachable.
     */
    private final ConcurrentHashMap<String, Boolean> localClaims = new ConcurrentHashMap<>();

    /**
     * Loads the order and executes the automatic refund for a cancelled order.
     *
     * <p>Convenience overload for callers that only have an order id; the order
     * is fetched through the repository so controllers never need to depend on
     * repositories directly.</p>
     *
     * @param orderId            id of the cancelled order
     * @param cancellationReason the cancellation reason used for policy resolution
     * @return {@code true} when a refund was executed, {@code false} otherwise
     * @throws ResourceNotFoundException if no order exists with the given id
     */
    public boolean autoRefund(Long orderId, String cancellationReason) {
        Order order = orderRepository.findByIdWithDetails(orderId)
                .orElseThrow(() -> new ResourceNotFoundException("Order not found"));
        return autoRefund(order, cancellationReason);
    }

    /**
     * Executes the automatic refund for a cancelled order if a policy applies.
     *
     * @param order              the cancelled order
     * @param cancellationReason the cancellation reason used for policy resolution
     * @return {@code true} when a refund was executed by this call, {@code false}
     *         when no policy applies, the refund was already processed, or the
     *         attempt failed (logged, never thrown)
     */
    public boolean autoRefund(Order order, String cancellationReason) {
        if (order == null || order.getId() == null) {
            log.warn("Auto refund skipped | order or order id is null");
            return false;
        }
        String idempotencyKey = "refund:" + order.getId() + ":" + cancellationReason;
        try {
            Optional<RefundPolicyService.RefundPolicy> policy =
                    refundPolicyService.computeRefund(order, cancellationReason);
            if (policy.isEmpty()) {
                log.info("No refund policy applies | orderId={} | reason={}", order.getId(), cancellationReason);
                return false;
            }
            if (!claimIdempotency(idempotencyKey)) {
                log.info("Auto refund already processed | key={}", idempotencyKey);
                return false;
            }

            Payment payment = paymentRepository.findByOrderId(order.getId()).orElse(null);
            if (payment != null && payment.getStatus() == Payment.PaymentStatus.REFUNDED) {
                log.info("Payment already refunded | orderId={} | paymentId={}", order.getId(), payment.getId());
                return false;
            }

            double amount = PriceCalculator.roundToTwoDecimals(
                    order.getTotalAmount() * policy.get().percent() / 100.0);
            String target = policy.get().target();

            if (RefundPolicyService.TARGET_WALLET.equalsIgnoreCase(target)) {
                walletService.credit(order.getCustomer().getId(), amount,
                        WalletTransaction.TransactionType.ORDER_REFUND, payment != null ? payment.getId() : null,
                        "Auto refund for cancelled order " + order.getOrderNumber());
                log.info("Auto refund to wallet completed | orderId={} | amount={}", order.getId(), amount);
                return true;
            }

            if (RefundPolicyService.TARGET_GATEWAY.equalsIgnoreCase(target)) {
                paymentService.processRefund(order.getId(), amount, cancellationReason);
                log.info("Auto refund via gateway completed | orderId={} | amount={} | reason={}",
                        order.getId(), amount, cancellationReason);
                return true;
            }

            releaseClaim(idempotencyKey);
            log.warn("Unknown refund target | orderId={} | target={}", order.getId(), target);
            return false;
        } catch (Exception ex) {
            releaseClaim(idempotencyKey);
            log.error("Auto refund failed | orderId={} | reason={}", order.getId(), cancellationReason, ex);
            return false;
        }
    }

    /**
     * Atomically claims an idempotency key in Redis (SETNX with TTL), falling
     * back to the local cache if Redis is unreachable. Returns {@code false}
     * when the key was already claimed, preventing duplicate refunds for the
     * same order and reason.
     */
    private boolean claimIdempotency(String key) {
        // Fast-path: check the local cache first.
        if (localClaims.containsKey(key)) {
            return false;
        }
        try {
            Boolean claimed = stringRedisTemplate.opsForValue()
                    .setIfAbsent(redisKey(key), "1", CLAIM_TTL);
            if (Boolean.TRUE.equals(claimed)) {
                localClaims.put(key, true);
                return true;
            }
            // Redis SETNX returned false (key already exists) — record locally.
            localClaims.put(key, true);
            return false;
        } catch (Exception ex) {
            log.warn("AUTO_REFUND_REDIS_FAILED | key={} | error={}", key, ex.getMessage());
            // Fallback to local-only claim.
            return localClaims.putIfAbsent(key, true) == null;
        }
    }

    /** Releases an idempotency claim from Redis and the local cache. */
    private void releaseClaim(String key) {
        localClaims.remove(key);
        try {
            stringRedisTemplate.delete(redisKey(key));
        } catch (Exception ex) {
            log.warn("AUTO_REFUND_REDIS_RELEASE_FAILED | key={} | error={}", key, ex.getMessage());
        }
    }

    private String redisKey(String key) {
        return REFUND_CLAIM_PREFIX + key;
    }
}