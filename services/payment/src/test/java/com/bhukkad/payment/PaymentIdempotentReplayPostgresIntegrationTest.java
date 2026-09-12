package com.bhukkad.payment;

import com.bhukkad.common.error.PaymentGatewayException;
import com.bhukkad.common.outbox.OutboxClient;
import com.bhukkad.payment.domain.entity.Payment;
import com.bhukkad.payment.domain.repository.PaymentRepository;
import com.bhukkad.payment.infrastructure.client.PaymentGateway;
import com.bhukkad.payment.domain.service.PaymentService;
import com.bhukkad.payment.domain.service.PaymentService.PaymentPropertiesGateway;
import com.bhukkad.payment.domain.service.WalletService;
import com.bhukkad.payment.domain.event.PaymentEventPublisher;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Feature-#1 regression (roadmap §1 risk mitigation): two identical requests
 * → exactly ONE wallet credit + ONE {@code payment_settled} outbox row, with
 * the settlement + outbox row + claim flip visible in REAL PostgreSQL through
 * the module's shared Testcontainers harness.
 *
 * <p>Also proves the replay-completes-credit property: a crash between the
 * settled commit and the wallet credit is closed by the second identical
 * request performing the missing credit exactly once (the claim payload's
 * {@code walletMovement} flag).</p>
 */
@SpringBootTest
class PaymentIdempotentReplayPostgresIntegrationTest extends AbstractPaymentPostgresTest {

    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private PaymentService paymentService;
    @Autowired private PaymentRepository paymentRepository;
    @Autowired private OutboxClient outboxClient;
    @Autowired private org.springframework.context.ApplicationContext applicationContext;

    private StubGateway stubGateway;
    private Object previousGatewayBean;

    static class StubGateway implements PaymentGateway {
        final AtomicInteger calls = new AtomicInteger();
        PaymentGateway.GatewayResult next = PaymentGateway.GatewayResult.ok("pay_stub", "order_stub");

        @Override
        public GatewayResult authorize(Long paymentId, Long customerId, BigDecimal amount, String currency) {
            calls.incrementAndGet();
            return next;
        }

        @Override
        public GatewayResult refund(Long paymentId, BigDecimal amount) {
            calls.incrementAndGet();
            return PaymentGateway.GatewayResult.ok("rfnd_stub");
        }
    }

    @BeforeEach
    void cleanSlate() {
        jdbcTemplate.update("DELETE FROM wallet_transactions");
        jdbcTemplate.update("DELETE FROM wallet_balances");
        jdbcTemplate.update("DELETE FROM outbox_events");
        jdbcTemplate.update("DELETE FROM idempotency_records");
        jdbcTemplate.update("DELETE FROM payments");
    }

    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    @Test
    void twoIdenticalRequests_exactlyOneWalletCreditAndOneSettledOutboxRow() {
        String key = "replay-topup-1";
        Payment first = paymentService.processPayment(0L, 42L, new BigDecimal("150.00"),
                Payment.METHOD_WALLET, key);
        // Second identical request (provider retry, user double-click): must
        // return the same payment and charge the PSP gateway exactly once.
        Payment second = paymentService.processPayment(0L, 42L, new BigDecimal("150.00"),
                Payment.METHOD_WALLET, key);

        assertThat(first.getId()).isEqualTo(second.getId());
        assertThat(first.getStatus()).isEqualTo(Payment.STATUS_SETTLED);

        assertThat(count("wallet_transactions", "type = 'CREDIT'")).isEqualTo(1);
        assertThat(count("wallet_transactions", "type = 'DEBIT'")).isEqualTo(0);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT balance FROM wallet_balances WHERE customer_id = 42", BigDecimal.class))
                .isEqualByComparingTo("150.00");

        assertThat(countOutbox("payment_settled")).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT count(*) FROM payments WHERE idempotency_key = ?", Long.class, key))
                .isEqualTo(1);
    }

    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    @Test
    void crashBetweenSettleAndCredit_replayCompletesTheCreditExactlyOnce() {
        String key = "replay-topup-2";
        // Settle WITHOUT the post-commit credit: simulate by flipping the
        // claim payload's walletMovement flag off after the first call.
        Payment first = paymentService.processPayment(0L, 43L, new BigDecimal("80.00"),
                Payment.METHOD_WALLET, key);
        assertThat(count("wallet_transactions", "type = 'CREDIT'")).isEqualTo(1);
        // Roll back the wallet credit rows only (the crash window), leaving
        // the settled payment + COMPLETED claim in place.
        jdbcTemplate.update("DELETE FROM wallet_transactions");
        jdbcTemplate.update("DELETE FROM wallet_balances");
        jdbcTemplate.update(
                "UPDATE idempotency_records SET response_payload = '{\"paymentId\":" + first.getId()
                        + ",\"walletMovement\":false}' WHERE idempotency_key = ?", key);

        Payment replay = paymentService.processPayment(0L, 43L, new BigDecimal("80.00"),
                Payment.METHOD_WALLET, key);

        assertThat(replay.getId()).isEqualTo(first.getId());
        // The replay completed the missing credit exactly once — no doubling.
        assertThat(count("wallet_transactions", "type = 'CREDIT'")).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT balance FROM wallet_balances WHERE customer_id = 43", BigDecimal.class))
                .isEqualByComparingTo("80.00");
    }

    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    @Test
    void declinedCharge_claimMarkedFailed_retryRecharges() {
        String key = "replay-decline-1";
        // Swap the PSP for a stub so the first authorize declines (the
        // simulated gateway would approve a 60.00 charge), then restore.
        stubGateway = new StubGateway();
        stubGateway.next = PaymentGateway.GatewayResult.failed("Simulated decline: card rejected");
        previousGatewayBean = applicationContext.getBean(PaymentGateway.class);
        org.springframework.test.util.ReflectionTestUtils.setField(paymentService, "paymentGateway", stubGateway);
        try {
            // First attempt: PSP declines (PENDING row created, then FAILED).
            try {
                paymentService.processPayment(10L, 44L, new BigDecimal("60.00"), "UPI", key);
                throw new AssertionError("expected PaymentGatewayException");
            } catch (PaymentGatewayException expected) {
                // terminal outcome surfaced to the caller
            }
            assertThat(jdbcTemplate.queryForObject(
                    "SELECT status FROM payments WHERE idempotency_key = ?", String.class, key))
                    .isEqualTo("FAILED");

            // Second attempt re-runs the unit (claim released, no residue);
            // the PSP approves this time (transient decline, retry settles).
            stubGateway.next = PaymentGateway.GatewayResult.ok("SIM-PROV-RETRY");
            Payment retry = paymentService.processPayment(10L, 44L, new BigDecimal("60.00"), "UPI", key);
            assertThat(retry.getStatus()).isEqualTo(Payment.STATUS_SETTLED);
            assertThat(countOutbox("payment_settled")).isEqualTo(1);
            assertThat(count("payments", "status = 'FAILED'")).isEqualTo(1);
            assertThat(count("payments", "status = 'SETTLED'")).isEqualTo(1);
        } finally {
            org.springframework.test.util.ReflectionTestUtils.setField(
                    paymentService, "paymentGateway", previousGatewayBean);
        }
    }

    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    @Test
    void walletOrderPayment_debitInsideSettleTransaction_allOrNothing() {
        jdbcTemplate.update("""
                INSERT INTO wallet_balances (customer_id, balance, version, updated_at)
                VALUES (45, 20.00, 0, localtimestamp)
                ON CONFLICT (customer_id) DO NOTHING
                """);

        assertThatThrownBy(() -> paymentService.processPayment(11L, 45L, new BigDecimal("50.00"),
                Payment.METHOD_WALLET, "replay-wallet-1"))
                .isInstanceOf(com.bhukkad.common.error.BusinessException.class)
                .hasMessageContaining("Insufficient");

        // Nothing settled, no event, wallet untouched.
        assertThat(count("payments", "status = 'SETTLED'")).isEqualTo(0);
        assertThat(countOutbox("payment_settled")).isEqualTo(0);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT balance FROM wallet_balances WHERE customer_id = 45", BigDecimal.class))
                .isEqualByComparingTo("20.00");
    }

    private long count(String table, String where) {
        Long n = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM " + table + " WHERE " + where, Long.class);
        return n == null ? 0 : n;
    }

    private long countOutbox(String eventType) {
        Long n = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM outbox_events WHERE event_type = ?", Long.class, eventType);
        return n == null ? 0 : n;
    }
}
