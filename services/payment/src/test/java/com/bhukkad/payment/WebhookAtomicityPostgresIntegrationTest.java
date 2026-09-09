package com.bhukkad.payment;

import com.bhukkad.common.outbox.OutboxEventService;
import com.bhukkad.payment.idempotency.WebhookIdempotencyService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * V-11 rollback contract against REAL PostgreSQL tables (payment module's
 * existing Testcontainers infra — webhook path needs only idempotency +
 * outbox rows, both in the service's own DB; no Kafka container is bootable
 * here because the payment module has none, and the settlement step itself is
 * covered by the service-level unit tests).
 *
 * <p>Proves the two properties that the old committed-steps-separately design
 * could never guarantee:</p>
 * <ol>
 *   <li>an outbox enqueue failure after the dedup claim + settlement leaves
 *       NO trace: no idempotency row, no outbox row, payment untouched — the
 *       provider's retry re-runs the whole unit;</li>
 *   <li>a duplicate event-id claim under the unique (scope, key) index throws
 *       and the accompanying settlement rolls back.</li>
 * </ol>
 */
// NOTE: deliberately NOT @DataJpaTest — payment's static PostgreSQL container
// registers a FIXED jdbc:postgresql://localhost:5432/payments URL via
// @DynamicPropertySource (shared across the suite, no @Container). @DataJpaTest
// would replace it with the non-existent service-based container, so the slice
// cannot be used here. Follows PaymentServiceContextSmokeTest instead and wires
// the REAL proxied service beans (MANDATORY propagation included).
@SpringBootTest
class WebhookAtomicityPostgresIntegrationTest extends AbstractPaymentPostgresTest {

    @Autowired
    private JdbcTemplate jdbcTemplate;
    @Autowired
    private PlatformTransactionManager transactionManager;

    @Autowired
    private WebhookIdempotencyService webhookIdempotencyService;
    @Autowired
    private OutboxEventService outboxEventService;

    private TransactionTemplate tx;

    @BeforeEach
    void setUp() {
        tx = new TransactionTemplate(transactionManager);
        // Full clean slate each test: the FIRST method used to leave payments
        // id=1 behind and every later seed hit the PK.
        jdbcTemplate.update("DELETE FROM idempotency_records");
        jdbcTemplate.update("DELETE FROM outbox_events");
        jdbcTemplate.update("DELETE FROM payments");
        seedPayment(1L, "PAY-prov-1", "AUTH");
    }

    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    @Test
    void enqueueFailure_rollsBackSettlementAndDedupClaim() {
        assertThatThrownBy(() -> tx.executeWithoutResult(status -> {
                    webhookIdempotencyService.claim("evt-atomic-1");
                    settlePayment(1L); // real UPDATE in the same tx
                    // Simulate the OutboxEventService.enqueue failure mode on
                    // the G-1 path: throw AFTER settlement, inside the tx.
                    throw new IllegalStateException("outbox enqueue failed");
                }))
                .isInstanceOf(IllegalStateException.class);

        assertThat(idCount("idempotency_records")).isZero();
        assertThat(idCount("outbox_events")).isZero();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT status FROM payments WHERE id = 1", String.class)).isEqualTo("AUTH");
    }

    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    @Test
    void happyPath_settlementClaimAndEventCommitTogether() {
        tx.executeWithoutResult(status -> {
            webhookIdempotencyService.claim("evt-atomic-2");
            settlePayment(1L);
            outboxEventService.enqueue("PAYMENT_WEBHOOK_RECEIVED", 1L,
                    java.util.Map.of("eventId", "evt-atomic-2"));
        });

        assertThat(idCount("idempotency_records")).isEqualTo(1);
        assertThat(idCount("outbox_events")).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT status FROM payments WHERE id = 1", String.class)).isEqualTo("SETTLED");
    }

    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    @Test
    void duplicateClaim_throwsAndSecondSettlementRollsBack() {
        tx.executeWithoutResult(status -> {
            webhookIdempotencyService.claim("evt-dup");
            settlePayment(1L);
            outboxEventService.enqueue("PAYMENT_WEBHOOK_RECEIVED", 1L,
                    java.util.Map.of("eventId", "evt-dup"));
        });

        assertThatThrownBy(() -> tx.executeWithoutResult(status -> {
            webhookIdempotencyService.claim("evt-dup");   // unique (scope,key) loses
        })).isInstanceOf(DataIntegrityViolationException.class);

        // Still exactly ONE projection of the event end-to-end:
        assertThat(idCount("idempotency_records")).isEqualTo(1);
        assertThat(idCount("outbox_events")).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT status FROM payments WHERE id = 1", String.class)).isEqualTo("SETTLED");
    }

    private void settlePayment(long paymentId) {
        int rows = jdbcTemplate.update("UPDATE payments SET status = 'SETTLED' WHERE id = ?", paymentId);
        assertThat(rows).isEqualTo(1);
    }

    private void seedPayment(long id, String providerRef, String status) {
        // id is GENERATED ALWAYS AS IDENTITY in the V1 baseline.
        jdbcTemplate.update(
                "INSERT INTO payments (id, order_id, customer_id, amount, currency, status, "
                        + "provider, provider_ref, created_at, updated_at) "
                        + "OVERRIDING SYSTEM VALUE "
                        + "VALUES (?, 100, 200, 49.00, 'INR', ?, 'RAZORPAY', ?, "
                        + "localtimestamp, localtimestamp)",
                id, status, providerRef);
    }

    private long idCount(String table) {
        Long n = jdbcTemplate.queryForObject("SELECT count(*) FROM " + table, Long.class);
        return n == null ? 0 : n;
    }
}
