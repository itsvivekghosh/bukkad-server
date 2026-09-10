package com.bhukkad.payment.consumer;

import com.bhukkad.common.event.PlatformEventMessage;
import com.bhukkad.payment.DisputeResolvedConsumer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * ADR-001 replay drill against REAL PostgreSQL: a duplicate
 * {@code dispute_resolved} (same OR different provider eventId, same dispute)
 * → exactly ONE wallet credit. The (DISPUTE_CREDIT, disputeId) idempotency row
 * and the wallet credit commit in one transaction through the module's shared
 * Testcontainers harness.
 */
@SpringBootTest
class DisputeResolvedCreditPostgresIntegrationTest extends AbstractPaymentPostgresTest {

    private static final String PAYLOAD =
            "{\"disputeId\":7,\"orderId\":30,\"customerId\":42,\"refundAmount\":\"150.00\",\"reason\":\"late delivery\"}";

    @Autowired private DisputeResolvedConsumer consumer;
    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private PlatformTransactionManager transactionManager;

    private TransactionTemplate tx;

    @BeforeEach
    void cleanSlate() {
        tx = new TransactionTemplate(transactionManager);
        jdbcTemplate.update("DELETE FROM wallet_transactions");
        jdbcTemplate.update("DELETE FROM wallet_balances");
        jdbcTemplate.update("DELETE FROM idempotency_records");
    }

    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    @Test
    void replayDrill_duplicateDisputeResolved_singleCredit() {
        // First delivery credits the wallet.
        tx.executeWithoutResult(s ->
                consumer.onDisputeResolved(PlatformEventMessage.of(
                        "dispute_resolved", "7", PAYLOAD)));

        // Provider redelivery of the SAME event (same or new eventId).
        tx.executeWithoutResult(s ->
                consumer.onDisputeResolved(PlatformEventMessage.of(
                        "dispute_resolved", "7", PAYLOAD)));
        tx.executeWithoutResult(s ->
                consumer.onDisputeResolved(PlatformEventMessage.of(
                        "dispute_resolved", "7", PAYLOAD.replace("150.00", "999.00"))));

        assertThat(jdbcTemplate.queryForObject(
                "SELECT count(*) FROM wallet_transactions WHERE reference = 'DISPUTE-7'", Long.class))
                .isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT balance FROM wallet_balances WHERE customer_id = 42", BigDecimal.class))
                .isEqualByComparingTo("150.00");
        assertThat(jdbcTemplate.queryForObject(
                "SELECT count(*) FROM idempotency_records WHERE scope = 'DISPUTE_CREDIT' "
                        + "AND idempotency_key = '7'", Long.class))
                .isEqualTo(1);
    }

    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    @Test
    void poisonPayload_throwsAndRollsBackWithNoResidue() {
        String badPayload = "{\"disputeId\":0,\"orderId\":30,\"customerId\":42,\"refundAmount\":\"150.00\"}";

        assertThatThrownBy(() -> tx.executeWithoutResult(s ->
                consumer.onDisputeResolved(PlatformEventMessage.of(
                        "dispute_resolved", "0", badPayload))))
                .isInstanceOf(com.bhukkad.common.kafka.PoisonEventException.class);

        // The claim must NOT be burned: a fixed event stays replayable.
        assertThat(jdbcTemplate.queryForObject(
                "SELECT count(*) FROM idempotency_records WHERE scope = 'DISPUTE_CREDIT'", Long.class))
                .isEqualTo(0);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT count(*) FROM wallet_transactions", Long.class)).isEqualTo(0);
    }
}
