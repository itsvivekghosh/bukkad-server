package com.bhukkad.payment;

import com.bhukkad.common.error.BusinessException;
import com.bhukkad.payment.domain.AgentCodWallet;
import com.bhukkad.payment.service.CodWalletService;
import com.bhukkad.payment.service.RiderEarningService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * V-02 finish acceptance on real PostgreSQL (migrations V1–V12): every COD
 * wallet balance mutation appends a {@code cod_wallet_ledger} row in the same
 * transaction, the {@code balance_after} chain is consistent end-to-end, the
 * earnings surface stays idempotent by {@code (agentId, orderId)}, and two
 * concurrent credits on one agent wallet each land exactly once (the
 * pessimistic FOR UPDATE + @Version pair loses no credit and records no ghost
 * entry). NOT_SUPPORTED runs the raced workers in their own committed
 * transactions (the default @DataJpaTest rollback tx would be invisible
 * across threads); transactional tests flush the persistence context before
 * raw-SQL reads because JdbcTemplate does not trigger auto-flush.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({CodWalletService.class, RiderEarningService.class})
class CodWalletLedgerPostgresIntegrationTest extends AbstractPaymentPostgresTest {

    private static final long AGENT = 77L;

    @Autowired private CodWalletService codWalletService;
    @Autowired private RiderEarningService earningService;
    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private TestEntityManager entityManager;

    @BeforeEach
    void clean() {
        jdbcTemplate.update("DELETE FROM cod_wallet_ledger");
        jdbcTemplate.update("DELETE FROM rider_earnings");
        jdbcTemplate.update("DELETE FROM agent_cod_wallets");
    }

    /** Ledger entries for the agent, in insert order, with parsed money scalars. */
    private record Entry(String type, long agentId, Long orderId, BigDecimal amount, BigDecimal balanceAfter) {
    }

    private List<Entry> ledgerEntries() {
        return jdbcTemplate.queryForList(
                        "SELECT agent_id, type, order_id, amount, balance_after "
                                + "FROM cod_wallet_ledger WHERE agent_id = " + AGENT + " ORDER BY id")
                .stream()
                .map(r -> new Entry((String) r.get("type"), (Long) r.get("agent_id"),
                        (Long) r.get("order_id"),
                        new BigDecimal(String.valueOf(r.get("amount"))),
                        new BigDecimal(String.valueOf(r.get("balance_after")))))
                .toList();
    }

    private BigDecimal walletBalance() {
        return jdbcTemplate.queryForObject(
                "SELECT balance FROM agent_cod_wallets WHERE agent_id = " + AGENT, BigDecimal.class);
    }

    private Long walletVersion() {
        return jdbcTemplate.queryForObject(
                "SELECT version FROM agent_cod_wallets WHERE agent_id = " + AGENT, Long.class);
    }

    /** Replays the ledger as a running balance — the audit monotonicity check. */
    private static BigDecimal replayedChain(List<Entry> entries) {
        BigDecimal running = BigDecimal.ZERO;
        for (Entry entry : entries) {
            running = "CREDIT".equals(entry.type())
                    ? running.add(entry.amount())
                    : running.subtract(entry.amount());
            assertThat(entry.balanceAfter()).isEqualByComparingTo(running);
        }
        return running;
    }

    // ── ledger append + balance_after chain ────────────────────────────────

    @Test
    void creditAndDebit_appendLedgerRowWithPersistedBalance() {
        codWalletService.credit(AGENT, new BigDecimal("100.00"), 900L);
        codWalletService.credit(AGENT, new BigDecimal("50.00"), null);
        codWalletService.debit(AGENT, new BigDecimal("30.00"), 901L);
        entityManager.flush(); // last balance UPDATE is still pending in the tx

        List<Entry> entries = ledgerEntries();
        assertThat(entries).hasSize(3);
        assertThat(entries.get(0).type()).isEqualTo("CREDIT");
        assertThat(entries.get(0).amount()).isEqualByComparingTo("100.00");
        assertThat(entries.get(0).balanceAfter()).isEqualByComparingTo("100.00");
        assertThat(entries.get(0).orderId()).isEqualTo(900L);
        assertThat(entries.get(1).orderId()).isNull(); // amount-only credit: order is attribution only
        assertThat(entries.get(2).type()).isEqualTo("DEBIT");
        assertThat(entries.get(2).orderId()).isEqualTo(901L);

        // The chain reproduces wallet history with no missing entries, and it
        // agrees with the authoritative row.
        assertThat(replayedChain(entries)).isEqualByComparingTo(new BigDecimal("120.00"));
        assertThat(walletBalance()).isEqualByComparingTo(new BigDecimal("120.00"));
        // @Version bumped exactly once per locked mutation (3 updates).
        assertThat(walletVersion()).isEqualTo(3L);
    }

    @Test
    void failedDebit_andNonPositiveAmounts_leaveNoLedgerEntry() {
        codWalletService.credit(AGENT, new BigDecimal("10.00"), null);

        assertThatThrownBy(() -> codWalletService.debit(AGENT, new BigDecimal("250.00"), null))
                .isInstanceOf(BusinessException.class)
                .hasMessage("Insufficient COD wallet balance");
        assertThatThrownBy(() -> codWalletService.credit(AGENT, BigDecimal.ZERO, null))
                .isInstanceOf(BusinessException.class)
                .hasMessage("Credit amount must be positive");
        assertThatThrownBy(() -> codWalletService.debit(AGENT, new BigDecimal("-1.00"), null))
                .isInstanceOf(BusinessException.class)
                .hasMessage("Debit amount must be positive");
        assertThatThrownBy(() -> codWalletService.debit(123456L, BigDecimal.ONE, null))
                .isInstanceOf(BusinessException.class)
                .hasMessage("COD wallet not found for agent 123456");
        entityManager.flush();

        assertThat(ledgerEntries()).hasSize(1); // guards fire before any write
        assertThat(walletBalance()).isEqualByComparingTo(new BigDecimal("10.00"));
    }

    // ── earnings: the (agentId, orderId) replay stays a no-op ──────────────

    @Test
    void duplicateRecordEarning_byAgentAndOrder_isANoOp() {
        RiderEarningService.EarningResult first =
                earningService.record(AGENT, 500L, new BigDecimal("49.50"));
        assertThat(first.duplicate()).isFalse();
        assertThat(first.earning().getStatus()).isEqualTo("EARNED");

        RiderEarningService.EarningResult replay =
                earningService.record(AGENT, 500L, new BigDecimal("99.99"));
        assertThat(replay.duplicate()).isTrue();
        assertThat(replay.earning()).isNull();

        assertThat(jdbcTemplate.queryForObject(
                "SELECT count(*) FROM rider_earnings WHERE agent_id = " + AGENT, Integer.class))
                .isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT amount FROM rider_earnings WHERE agent_id = " + AGENT, BigDecimal.class))
                .isEqualByComparingTo("49.50"); // the replay neither wrote nor re-priced
        // A distinct order for the same rider stays payable.
        assertThat(earningService.record(AGENT, 501L, new BigDecimal("12.00")).duplicate()).isFalse();
    }

    @Test
    void recordEarning_keepsAmountGuards() {
        assertThatThrownBy(() -> earningService.record(AGENT, 1L, new BigDecimal("-0.01")))
                .isInstanceOf(BusinessException.class)
                .hasMessage("Earning amount must be positive");
        assertThatThrownBy(() -> earningService.record(AGENT, 2L, new BigDecimal("10000.01")))
                .isInstanceOf(BusinessException.class)
                .hasMessage("Earning amount exceeds the per-delivery limit");
    }

    @Test
    void markPaid_onlyTransitionsEarnedRows() {
        Long earningId = earningService.record(AGENT, 600L, BigDecimal.ONE).earning().getId();
        earningService.markPaid(earningId);

        assertThatThrownBy(() -> earningService.markPaid(earningId))
                .isInstanceOf(BusinessException.class); // no replay of EARNED→PAID
        assertThat(jdbcTemplate.queryForObject(
                "SELECT status FROM rider_earnings WHERE id = " + earningId, String.class))
                .isEqualTo("PAID");
    }

    // ── concurrency: two credits, each applied exactly once ─────────────────

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void concurrentCredits_eachLandsOnce_ledgerChainStaysIntact() throws Exception {
        codWalletService.credit(AGENT, new BigDecimal("100.00"), null); // seed (own tx)

        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch go = new CountDownLatch(1);
        AtomicLong attribution = new AtomicLong();
        CopyOnWriteArrayList<Throwable> unexpected = new CopyOnWriteArrayList<>();
        CopyOnWriteArrayList<AgentCodWallet> results = new CopyOnWriteArrayList<>();
        for (int t = 0; t < 2; t++) {
            pool.submit(() -> {
                try {
                    ready.countDown();
                    go.await(10, TimeUnit.SECONDS);
                    results.add(codWalletService.credit(
                            AGENT, new BigDecimal("50.00"), attribution.incrementAndGet()));
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                } catch (Throwable other) {
                    unexpected.add(other);
                }
            });
        }
        ready.await();
        go.countDown();
        pool.shutdown();
        assertThat(pool.awaitTermination(30, TimeUnit.SECONDS)).isTrue();

        assertThat(unexpected).isEmpty();
        assertThat(results).hasSize(2);
        // No lost update: both 50.00 credits landed on top of the 100.00 seed.
        assertThat(walletBalance()).isEqualByComparingTo(new BigDecimal("200.00"));
        assertThat(walletVersion()).isEqualTo(3L);

        // Exactly one ledger entry per applied credit (1 seed + 2 raced),
        // serialized balance_after chain 100 → 150 → 200, both attributions
        // present exactly once.
        List<Entry> entries = ledgerEntries();
        assertThat(entries).hasSize(3);
        assertThat(entries).allMatch(e -> "CREDIT".equals(e.type()));
        assertThat(replayedChain(entries)).isEqualByComparingTo(new BigDecimal("200.00"));
        assertThat(entries.stream().map(Entry::orderId).filter(java.util.Objects::nonNull).distinct())
                .containsExactlyInAnyOrder(1L, 2L);
    }

    // ── schema guards shipped by V11/V12 ───────────────────────────────────

    @Test
    void migrations_createLedgerAndWalletGuards() {
        assertThat(jdbcTemplate.queryForObject(
                "SELECT count(*) FROM information_schema.columns "
                        + "WHERE table_name = 'agent_cod_wallets' AND column_name = 'version'",
                Integer.class)).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT count(*) FROM pg_constraint WHERE conname = 'uk_agent_cod_wallet_agent'",
                Integer.class)).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT count(*) FROM pg_indexes WHERE tablename = 'cod_wallet_ledger' "
                        + "AND indexname = 'idx_cod_ledger_agent_created'",
                Integer.class)).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT data_type FROM information_schema.columns "
                        + "WHERE table_name = 'cod_wallet_ledger' AND column_name = 'amount'",
                String.class)).isEqualTo("numeric");
        assertThat(jdbcTemplate.queryForObject(
                "SELECT character_maximum_length FROM information_schema.columns "
                        + "WHERE table_name = 'cod_wallet_ledger' AND column_name = 'type'",
                Integer.class)).isEqualTo(6);
        // order_id is nullable by contract (amount-only callers).
        assertThat(jdbcTemplate.queryForObject(
                "SELECT is_nullable FROM information_schema.columns "
                        + "WHERE table_name = 'cod_wallet_ledger' AND column_name = 'order_id'",
                String.class)).isEqualTo("YES");
    }
}
