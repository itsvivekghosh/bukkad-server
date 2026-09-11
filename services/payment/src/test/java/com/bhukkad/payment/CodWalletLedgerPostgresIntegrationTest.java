package com.bhukkad.payment;

import com.bhukkad.common.error.BusinessException;
import com.bhukkad.payment.service.CodWalletService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * V-02 finish acceptance on real PostgreSQL (migrations V1-V12): every COD
 * wallet movement appends exactly one {@code cod_wallet_ledger} row inside
 * the same transaction, {@code balance_after} matches the persisted wallet
 * state after each movement (replayable, monotonic running total), duplicate
 * earning credits for the same {@code (agentId, orderId)} are a no-op, and
 * concurrent debits can never double-spend. NOT_SUPPORTED runs each worker
 * in its own committed transaction (the default @DataJpaTest rollback tx
 * would be invisible across threads).
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import(CodWalletService.class)
class CodWalletLedgerPostgresIntegrationTest extends AbstractPaymentPostgresTest {

    private static final long AGENT = 7788L;

    @Autowired private CodWalletService codWalletService;
    @Autowired private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void clean() {
        jdbcTemplate.update("DELETE FROM cod_wallet_ledger");
        jdbcTemplate.update("DELETE FROM rider_earnings");
        jdbcTemplate.update("DELETE FROM agent_cod_wallets");
    }

    private BigDecimal walletBalance() {
        return jdbcTemplate.queryForObject(
                "SELECT balance FROM agent_cod_wallets WHERE agent_id = " + AGENT,
                BigDecimal.class);
    }

    private record LedgerRow(long id, String type, String amount, String balanceAfter) {
    }

    private List<LedgerRow> ledger() {
        return jdbcTemplate.query(
                "SELECT id, type, amount::text AS amount, balance_after::text AS balance_after "
                        + "FROM cod_wallet_ledger WHERE agent_id = ? ORDER BY id",
                (rs, n) -> new LedgerRow(rs.getLong("id"), rs.getString("type"),
                        rs.getString("amount"), rs.getString("balance_after")),
                AGENT);
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void creditAndDebit_appendOneLedgerRowEach_withBalanceAfterMatchingPersistedWallet() {
        codWalletService.credit(AGENT, new BigDecimal("100.00"));
        codWalletService.credit(AGENT, new BigDecimal("50.50"));
        codWalletService.debit(AGENT, new BigDecimal("30.25"));

        List<LedgerRow> rows = ledger();
        assertThat(rows).hasSize(3);
        assertThat(rows).extracting(LedgerRow::type)
                .containsExactly("CREDIT", "CREDIT", "DEBIT");
        assertThat(rows).extracting(LedgerRow::amount)
                .containsExactly("100.00", "50.50", "30.25");
        // balance_after is the running total as PERSISTED per movement — a
        // reconciliation replay of (agent_id, created_at) is monotonic.
        assertThat(rows).extracting(LedgerRow::balanceAfter)
                .containsExactly("100.00", "150.50", "120.25");
        assertThat(walletBalance()).isEqualByComparingTo("120.25");
        // The ledger ends where the wallet is.
        assertThat(rows.get(rows.size() - 1).balanceAfter())
                .isEqualTo(walletBalance().toPlainString());
        // Optimistic fence: the @Version column is maintained by the locked path.
        assertThat(jdbcTemplate.queryForObject(
                "SELECT version FROM agent_cod_wallets WHERE agent_id = " + AGENT, Long.class))
                .isEqualTo(3L);
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void failedDebit_leavesNoLedgerRowAndNoBalanceChange() {
        assertThatThrownBy(() -> codWalletService.debit(AGENT, new BigDecimal("10.00")))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("not found");

        codWalletService.credit(AGENT, new BigDecimal("5.00"));
        assertThatThrownBy(() -> codWalletService.debit(AGENT, new BigDecimal("9.00")))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("Insufficient COD wallet balance");

        // Rolled-back mutations leave no balance evidence behind.
        assertThat(ledger()).hasSize(1);
        assertThat(ledger().get(0).type()).isEqualTo("CREDIT");
        assertThat(walletBalance()).isEqualByComparingTo("5.00");
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void duplicateEarningForSameAgentOrder_isNoOp_withoutLedgerSideEffects() {
        CodWalletService.EarningRecord first =
                codWalletService.recordEarning(AGENT, 555L, new BigDecimal("49.50"));
        assertThat(first.duplicate()).isFalse();
        assertThat(first.earning().getStatus()).isEqualTo("EARNED");

        // Retry/replay of the same (agentId, orderId): no second payable row.
        CodWalletService.EarningRecord replay =
                codWalletService.recordEarning(AGENT, 555L, new BigDecimal("49.50"));
        assertThat(replay.duplicate()).isTrue();
        assertThat(replay.earning()).isNull();

        // A different order on the same agent remains payable.
        assertThat(codWalletService.recordEarning(AGENT, 556L, new BigDecimal("10.00"))
                .duplicate()).isFalse();

        assertThat(jdbcTemplate.queryForObject(
                "SELECT count(*) FROM rider_earnings WHERE agent_id = " + AGENT, Integer.class))
                .isEqualTo(2);
        // Earnings are payable records, not wallet movements: no ledger rows.
        assertThat(ledger()).isEmpty();
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void concurrentDebits_neverDoubleSpend_andLedgerMatchesSuccessfulOps() throws Exception {
        codWalletService.credit(AGENT, new BigDecimal("100.00"));

        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch go = new CountDownLatch(1);
        AtomicInteger succeeded = new AtomicInteger();
        AtomicInteger insufficient = new AtomicInteger();
        CopyOnWriteArrayList<Throwable> unexpected = new CopyOnWriteArrayList<>();
        for (int t = 0; t < 2; t++) {
            pool.submit(() -> {
                try {
                    go.await(10, TimeUnit.SECONDS);
                    codWalletService.debit(AGENT, new BigDecimal("60.00"));
                    succeeded.incrementAndGet();
                } catch (BusinessException expected) {
                    insufficient.incrementAndGet();
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                } catch (Throwable other) {
                    unexpected.add(other);
                }
            });
        }
        go.countDown();
        pool.shutdown();
        assertThat(pool.awaitTermination(60, TimeUnit.SECONDS)).isTrue();

        assertThat(unexpected).isEmpty();
        // Two debits of 60.00 vs 100.00 funded: exactly one may succeed —
        // the FOR UPDATE lock means no double-spend and no negative balance.
        assertThat(succeeded.get()).isEqualTo(1);
        assertThat(insufficient.get()).isEqualTo(1);
        assertThat(walletBalance()).isEqualByComparingTo("40.00");

        // The ledger agrees with the wallet: 1 CREDIT + exactly 1 DEBIT, and
        // balance_after equals the final persisted balance.
        List<LedgerRow> rows = ledger();
        assertThat(rows).extracting(LedgerRow::type).containsExactly("CREDIT", "DEBIT");
        assertThat(rows.get(1).balanceAfter()).isEqualTo("40.00");
        assertThat(rows.get(rows.size() - 1).balanceAfter())
                .isEqualTo(walletBalance().toPlainString());
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void v11AndV12Migrations_carryLedgerTableAndWalletIdentityGuards() {
        assertThat(jdbcTemplate.queryForList(
                "SELECT column_name FROM information_schema.columns "
                        + "WHERE table_name = 'cod_wallet_ledger' ORDER BY ordinal_position", String.class))
                .containsExactly("id", "agent_id", "order_id", "type", "amount",
                        "balance_after", "created_at");
        assertThat(jdbcTemplate.queryForObject(
                "SELECT count(*) FROM pg_indexes WHERE tablename = 'cod_wallet_ledger' "
                        + "AND indexname = 'idx_cod_ledger_agent'", Integer.class))
                .isEqualTo(1);

        assertThat(jdbcTemplate.queryForObject(
                "SELECT count(*) FROM information_schema.columns "
                        + "WHERE table_name = 'agent_cod_wallets' AND column_name = 'version'",
                Integer.class)).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT count(*) FROM pg_constraint WHERE conname = 'uk_agent_cod_wallet_agent'",
                Integer.class)).isEqualTo(1);
    }
}
