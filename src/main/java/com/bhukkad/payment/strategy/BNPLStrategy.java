package com.bhukkad.payment.strategy;

import com.bhukkad.entity.Order;
import com.bhukkad.entity.Payment;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Buy-now-pay-later payment strategy.
 *
 * <p>Eligibility is validated against the customer's outstanding BNPL balance,
 * which is tracked in Redis so every replica sees the same pending balance and
 * the credit-limit invariant ({@link #MAX_PENDING_BALANCE}) holds under
 * horizontal scaling. The claim (check balance + add amount) is executed as a
 * single atomic Lua script to prevent two replicas from approving orders that
 * jointly exceed the limit.</p>
 *
 * <p>Degradation: when Redis is unreachable the balance cannot be verified, so
 * BNPL eligibility fails closed (no new credit extended) — a safe degradation
 * for a credit product.</p>
 */
@Slf4j
@Component
public class BNPLStrategy implements PaymentStrategy {

    public static final double MIN_ORDER_AMOUNT = 1000.0;
    public static final double MAX_PENDING_BALANCE = 5000.0;

    /** Redis key prefix for per-customer pending BNPL balances. */
    static final String PENDING_KEY_PREFIX = "bhukkad:bnpl:pending:";

    /**
     * Atomically: if current balance + amount &lt;= limit, add amount and return 1;
     * otherwise return 0. Single round-trip, no TOCTOU race between replicas.
     */
    static final DefaultRedisScript<Long> CLAIM_IF_AVAILABLE = new DefaultRedisScript<>(
            """
                    local balance = tonumber(redis.call('GET', KEYS[1]) or '0')
                    local amount = tonumber(ARGV[1])
                    local limit = tonumber(ARGV[2])
                    if balance + amount <= limit then
                      redis.call('SET', KEYS[1], balance + amount)
                      return 1
                    end
                    return 0
                    """,
            Long.class);

    private final StringRedisTemplate stringRedisTemplate;

    public BNPLStrategy(StringRedisTemplate stringRedisTemplate) {
        this.stringRedisTemplate = stringRedisTemplate;
    }

    /** Last-known balance per customer, used as a read-path cache and Redis-failure fallback. */
    private final ConcurrentHashMap<Long, Double> cachedBalances = new ConcurrentHashMap<>();

    @Override
    public Payment process(PaymentContext context) {
        Payment payment = context.payment();
        Order order = context.order();
        Long customerId = order.getCustomer() != null ? order.getCustomer().getId() : null;
        double amount = order.getTotalAmount() != null ? order.getTotalAmount() : context.gatewayAmount();

        if (customerId == null || amount <= 0 || !isEligible(customerId, amount)) {
            payment.setStatus(Payment.PaymentStatus.FAILED);
            payment.setPaymentGatewayResponse("BNPL_INELIGIBLE");
            log.warn("BNPL payment rejected | customerId={} | orderId={} | amount={}",
                    customerId, order.getId(), amount);
            return payment;
        }

        if (!claim(customerId, amount)) {
            payment.setStatus(Payment.PaymentStatus.FAILED);
            payment.setPaymentGatewayResponse("BNPL_LIMIT_EXCEEDED");
            log.warn("BNPL limit exceeded | customerId={} | orderId={} | amount={}",
                    customerId, order.getId(), amount);
            return payment;
        }

        payment.setTransactionId("BNPL-" + order.getOrderNumber());
        payment.setPaymentGatewayResponse("BNPL_APPROVED");
        payment.setStatus(Payment.PaymentStatus.COMPLETED);
        payment.setCompletedAt(LocalDateTime.now());
        log.info("BNPL payment approved | customerId={} | orderId={} | amount={} | pending={}",
                customerId, order.getId(), amount, pendingBalance(customerId));
        return payment;
    }

    public boolean isEligible(Long customerId, double amount) {
        if (customerId == null || amount <= MIN_ORDER_AMOUNT) {
            return false;
        }
        return pendingBalance(customerId) + amount <= MAX_PENDING_BALANCE;
    }

    public boolean isEligibleForNewOrder(Long customerId) {
        return customerId != null && pendingBalance(customerId) < MAX_PENDING_BALANCE;
    }

    public double pendingBalance(Long customerId) {
        if (customerId == null) {
            return 0.0;
        }
        try {
            String value = stringRedisTemplate.opsForValue().get(key(customerId));
            if (value != null) {
                double balance = Double.parseDouble(value);
                cachedBalances.put(customerId, balance);
                return balance;
            }
            // No key yet: this customer has no outstanding BNPL balance.
            cachedBalances.put(customerId, 0.0);
            return 0.0;
        } catch (Exception ex) {
            log.warn("BNPL balance read failed | customerId={} | error={}", customerId, ex.getMessage());
            // Fail closed: if the balance cannot be verified, assume the limit is reached.
            return cachedBalances.getOrDefault(customerId, MAX_PENDING_BALANCE);
        }
    }

    /** Releases (decrements) a customer's pending BNPL balance, e.g. on repayment. */
    public void releaseBalance(Long customerId, double amount) {
        if (customerId == null || amount <= 0) {
            return;
        }
        try {
            Long result = stringRedisTemplate.execute(
                    new DefaultRedisScript<>(
                            """
                                    local balance = tonumber(redis.call('GET', KEYS[1]) or '0')
                                    local next = math.max(0, balance - tonumber(ARGV[1]))
                                    redis.call('SET', KEYS[1], next)
                                    return next
                                    """,
                            Long.class),
                    List.of(key(customerId)),
                    String.valueOf(amount));
            if (result != null) {
                cachedBalances.put(customerId, result.doubleValue());
            }
        } catch (Exception ex) {
            log.warn("BNPL balance release failed | customerId={} | amount={} | error={}",
                    customerId, amount, ex.getMessage());
        }
    }

    /** Atomically claims {@code amount} against the customer's pending balance. */
    private boolean claim(Long customerId, double amount) {
        try {
            Long claimed = stringRedisTemplate.execute(
                    CLAIM_IF_AVAILABLE,
                    List.of(key(customerId)),
                    String.valueOf(amount), String.valueOf(MAX_PENDING_BALANCE));
            boolean success = claimed != null && claimed == 1L;
            if (success) {
                cachedBalances.merge(customerId, amount, Double::sum);
            }
            return success;
        } catch (Exception ex) {
            log.warn("BNPL claim failed | customerId={} | amount={} | error={}",
                    customerId, amount, ex.getMessage());
            return false;
        }
    }

    private String key(Long customerId) {
        return PENDING_KEY_PREFIX + customerId;
    }
}
