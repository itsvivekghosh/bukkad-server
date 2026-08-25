package com.bhukkad.payment.strategy;

import com.bhukkad.entity.Customer;
import com.bhukkad.entity.Order;
import com.bhukkad.entity.Payment;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.invocation.InvocationOnMock;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.data.redis.core.script.DefaultRedisScript;

import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link BNPLStrategy}. Redis is simulated with an in-memory
 * store whose {@code execute} answers reproduce the exact Lua claim semantics,
 * so multi-instance (multi-replica) behaviour can be verified deterministically.
 */
class BNPLStrategyTest {

    private ConcurrentMap<String, String> redisStore;
    private StringRedisTemplate redisTemplate;
    private ValueOperations<String, String> valueOps;
    /** When set, valueOps.get() throws (simulates a Redis outage). */
    private volatile RuntimeException getFailure;
    /** When set, valueOps.get() returns this stale value (simulates a race). */
    private volatile String pendingReadOverride;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        redisStore = new ConcurrentHashMap<>();
        redisTemplate = mock(StringRedisTemplate.class);
        valueOps = mock(ValueOperations.class);
        when(redisTemplate.opsForValue()).thenReturn(valueOps);
        when(valueOps.get(any())).thenAnswer(inv -> {
            if (getFailure != null) {
                throw getFailure;
            }
            if (pendingReadOverride != null) {
                return pendingReadOverride;
            }
            return redisStore.get(inv.getArgument(0));
        });
        // Claim script executes with 2 varargs (amount, limit); release script with 1 (amount).
        when(redisTemplate.execute(any(DefaultRedisScript.class), anyList(), any(), any()))
                .thenAnswer(this::executeScript);
        when(redisTemplate.execute(any(DefaultRedisScript.class), anyList(), any()))
                .thenAnswer(this::executeScript);
    }

    /** Reproduces the CLAIM_IF_AVAILABLE / release Lua scripts against the in-memory store. */
    private Long executeScript(InvocationOnMock inv) {
        DefaultRedisScript<Long> script = inv.getArgument(0);
        String key = (String) inv.getArgument(1, List.class).get(0);
        // Varargs: the remaining arguments are the script args.
        Object[] rawArgs = new Object[inv.getArguments().length - 2];
        System.arraycopy(inv.getArguments(), 2, rawArgs, 0, rawArgs.length);
        double amount = Double.parseDouble(String.valueOf(rawArgs[0]));
        if (script.getScriptAsString().contains("math.max")) {
            // release script
            double current = redisStore.containsKey(key) ? Double.parseDouble(redisStore.get(key)) : 0.0;
            double next = Math.max(0, current - amount);
            redisStore.put(key, String.valueOf(next));
            return (long) next;
        }
        // claim script
        double limit = Double.parseDouble(String.valueOf(rawArgs[1]));
        double current = redisStore.containsKey(key) ? Double.parseDouble(redisStore.get(key)) : 0.0;
        if (current + amount <= limit) {
            redisStore.put(key, String.valueOf(current + amount));
            return 1L;
        }
        return 0L;
    }

    private BNPLStrategy strategy() {
        return new BNPLStrategy(redisTemplate);
    }

    private Order order(Long customerId, double total) {
        Order order = new Order();
        order.setId(10L);
        order.setOrderNumber("ORD-10");
        order.setTotalAmount(total);
        if (customerId != null) {
            Customer customer = new Customer();
            customer.setId(customerId);
            order.setCustomer(customer);
        }
        return order;
    }

    private PaymentContext context(Order order) {
        Payment payment = new Payment();
        payment.setId(1L);
        payment.setAmount(order.getTotalAmount() != null ? order.getTotalAmount() : 0);
        return new PaymentContext(order, payment, "test-idem", order.getTotalAmount() != null ? order.getTotalAmount() : 0);
    }

    @Test
    void process_eligible_marksCompletedAndRecordsBalance() {
        Payment result = strategy().process(context(order(1L, 2000.0)));

        assertEquals(Payment.PaymentStatus.COMPLETED, result.getStatus());
        assertEquals("BNPL-ORD-10", result.getTransactionId());
        assertEquals("BNPL_APPROVED", result.getPaymentGatewayResponse());
        assertEquals(2000.0, Double.parseDouble(redisStore.get("bhukkad:bnpl:pending:1")), 0.001);
    }

    @Test
    void process_secondOrderWithinLimit_approved() {
        BNPLStrategy svc = strategy();
        svc.process(context(order(1L, 2000.0)));

        Payment second = svc.process(context(order(1L, 2000.0)));

        assertEquals(Payment.PaymentStatus.COMPLETED, second.getStatus());
        assertEquals(4000.0, Double.parseDouble(redisStore.get("bhukkad:bnpl:pending:1")), 0.001);
    }

    @Test
    void process_balanceOverLimit_rejectedAsIneligibleByPreCheck() {
        BNPLStrategy svc = strategy();
        svc.process(context(order(1L, 2000.0)));

        Payment second = svc.process(context(order(1L, 4000.0)));

        assertEquals(Payment.PaymentStatus.FAILED, second.getStatus());
        assertEquals("BNPL_INELIGIBLE", second.getPaymentGatewayResponse());
        // Balance untouched by the rejected claim.
        assertEquals(2000.0, Double.parseDouble(redisStore.get("bhukkad:bnpl:pending:1")), 0.001);
    }

    @Test
    void process_staleBalanceRead_claimRace_rejectsWithLimitExceeded() {
        // Simulate a race: this replica reads a stale (low) balance so the
        // pre-check passes, but the store already holds the limit — the atomic
        // claim script must reject and report BNPL_LIMIT_EXCEEDED.
        redisStore.put("bhukkad:bnpl:pending:1", String.valueOf(BNPLStrategy.MAX_PENDING_BALANCE));
        // Stale read: the pre-check sees 0, but the atomic claim script consults
        // the store, which already holds the limit.
        pendingReadOverride = "0";
        BNPLStrategy svc = strategy();

        Payment result = svc.process(context(order(1L, 2000.0)));

        assertEquals(Payment.PaymentStatus.FAILED, result.getStatus());
        assertEquals("BNPL_LIMIT_EXCEEDED", result.getPaymentGatewayResponse());
    }

    @Test
    void process_amountBelowMinimum_rejectedIneligible() {
        Payment result = strategy().process(context(order(1L, 500.0)));

        assertEquals(Payment.PaymentStatus.FAILED, result.getStatus());
        assertEquals("BNPL_INELIGIBLE", result.getPaymentGatewayResponse());
        assertFalse(redisStore.containsKey("bhukkad:bnpl:pending:1"));
    }

    @Test
    void process_nullCustomer_rejectedIneligible() {
        Payment result = strategy().process(context(order(null, 2000.0)));

        assertEquals(Payment.PaymentStatus.FAILED, result.getStatus());
        assertEquals("BNPL_INELIGIBLE", result.getPaymentGatewayResponse());
    }

    @Test
    void process_zeroAmount_rejectedIneligible() {
        Payment result = strategy().process(context(order(1L, 0.0)));

        assertEquals(Payment.PaymentStatus.FAILED, result.getStatus());
        assertEquals("BNPL_INELIGIBLE", result.getPaymentGatewayResponse());
    }

    @Test
    void isEligible_nullCustomer_false() {
        assertFalse(strategy().isEligible(null, 2000.0));
    }

    @Test
    void isEligible_belowMinimum_false() {
        assertFalse(strategy().isEligible(1L, 900.0));
    }

    @Test
    void isEligible_aboveLimit_false() {
        strategy().process(context(order(1L, 4000.0)));
        assertFalse(strategy().isEligible(1L, 1500.0));
    }

    @Test
    void isEligible_unknownCustomer_true() {
        assertTrue(strategy().isEligible(1L, 2000.0));
    }

    @Test
    void isEligibleForNewOrder_nullCustomer_false() {
        assertFalse(strategy().isEligibleForNewOrder(null));
    }

    @Test
    void isEligibleForNewOrder_atLimit_false() {
        strategy().process(context(order(1L, 5000.0)));
        assertFalse(strategy().isEligibleForNewOrder(1L));
    }

    @Test
    void pendingBalance_unknownCustomer_zero() {
        assertEquals(0.0, strategy().pendingBalance(42L), 0.001);
    }

    @Test
    void pendingBalance_nullCustomer_zero() {
        assertEquals(0.0, strategy().pendingBalance(null), 0.001);
    }

    @Test
    void pendingBalance_afterApproval_matchesStore() {
        BNPLStrategy svc = strategy();
        svc.process(context(order(1L, 2500.0)));
        assertEquals(2500.0, svc.pendingBalance(1L), 0.001);
    }

    @Test
    void pendingBalance_redisDown_failsClosed() {
        getFailure = new IllegalStateException("redis down");

        assertEquals(BNPLStrategy.MAX_PENDING_BALANCE, strategy().pendingBalance(1L), 0.001);
    }

    @Test
    void releaseBalance_reducesPending() {
        BNPLStrategy svc = strategy();
        svc.process(context(order(1L, 3000.0)));

        svc.releaseBalance(1L, 1000.0);

        assertEquals(2000.0, Double.parseDouble(redisStore.get("bhukkad:bnpl:pending:1")), 0.001);
    }

    @Test
    void releaseBalance_nullCustomer_noop() {
        BNPLStrategy svc = strategy();
        svc.releaseBalance(null, 100.0);
        assertTrue(redisStore.isEmpty());
    }

    @Test
    void releaseBalance_nonPositiveAmount_noop() {
        BNPLStrategy svc = strategy();
        svc.process(context(order(1L, 3000.0)));

        svc.releaseBalance(1L, 0.0);

        assertEquals(3000.0, Double.parseDouble(redisStore.get("bhukkad:bnpl:pending:1")), 0.001);
    }

    @Test
    void replicaApprovalIsVisibleToOtherReplica() {
        BNPLStrategy replicaA = strategy();
        BNPLStrategy replicaB = strategy();

        replicaA.process(context(order(1L, 2000.0)));

        // Replica B sees A's claim and rejects a claim that would exceed the limit.
        assertFalse(replicaB.isEligible(1L, 4000.0));
        assertEquals(2000.0, replicaB.pendingBalance(1L), 0.001);
        Payment rejected = replicaB.process(context(order(1L, 4000.0)));
        assertEquals(Payment.PaymentStatus.FAILED, rejected.getStatus());
        assertEquals("BNPL_INELIGIBLE", rejected.getPaymentGatewayResponse());
    }
}
