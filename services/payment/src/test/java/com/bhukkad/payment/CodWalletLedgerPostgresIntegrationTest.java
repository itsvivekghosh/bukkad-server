package com.bhukkad.payment;

import com.bhukkad.common.error.BusinessException;
import com.bhukkad.payment.domain.AgentCodWalletRepository;
import com.bhukkad.payment.domain.CodWalletLedger;
import com.bhukkad.payment.domain.CodWalletLedgerRepository;
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
 * V-02 finish money-safety acceptance on real PostgreSQL (migration V11):
 * every committed credit/debit on a rider COD wallet must append exactly one
 * {@code cod_wallet_ledger} row inside the same transaction, the
 * {@code balance_after} chain must be monotonic per agent, duplicate earnings
 * must stay no-ops, and the UNIQUE(agent_id) + @Version guards must exist.
 * NOT_SUPPORTED runs each worker in its own committed transaction (the
 * default @DataJpaTest rollback tx would be invisible across threads).
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import(CodWalletService.class)
class CodWalletLedgerPostgresIntegrationTest extends AbstractPaymentPostgresTest {

    private static final long AGENT = 909L;
    private static final BigDecimal MAX_EARNING_AMOUNT = new BigDecimal("10000.00");

    @Autowired private CodWalletService codWalletService;
    @Autowired private CodWalletLedgerRepository ledgerRepository;
    @Autowired private AgentCodWalletRepository walletRepository;
    @Autowired private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void clean() {
        jdbcTemplate.update("DELETE FROM cod_wallet_ledger");
        jdbcTemplate.update("DELETE FROM rider_earnings");
        jdbcTemplate.update("DELETE FROM agent_cod_wallets");
    }

    // ===== ledger appended per mutation, same tx, monotonic balance_after =====

    @Test
    void creditAndDebit_appendLedgerRows_balanceAfterChainIsMonotonic() {
        codWalletService.credit(AGENT, new BigDecimal("100.00"));
        codWalletService.credit(AGENT, new BigDecimal("50.00"));
        codWalletService.debit(AGENT, new BigDecimal("30.00"));

        List<CodWalletLedger> ledger = ledgerRepository.findByAgentIdOrderByIdAsc(AGENT);

        assertThat(ledger).hasSize(3);
        assertThat(ledger).extracting(CodWalletLedger::getType)
                .containsExactly("CREDIT", "CREDIT", "DEBIT");
        assertThat(ledger).extracting(CodWalletLedger::getAmount)
                .usingElementComparator(BigDecimal::compareTo)
                .containsExactly(
                        new BigDecimal("100.00"), new BigDecimal("50.00"), new BigDecimal("30.00"));

        // Monotonic audit chain: every balance_after is the persisted balance
        // after its own mutation — prev ± amount, never negative.
        BigDecimal running = BigDecimal.ZERO;
        for (CodWalletLedger entry : ledger) {
            running = "CREDIT".equals(entry.getType())
                    ? running.add(entry.getAmount())
                    : running.subtract(entry.getAmount());
            assertThat(entry.getBalanceAfter()).isEqualByComparingTo(running);
            assertThat(entry.getBalanceAfter()).isNotNegative();
        }
        assertThat(ledger.get(2).getBalanceAfter()).isEqualByComparingTo("120.00");

        // The audited balance row agrees with the last ledger entry.
        assertThat(codWalletService.codWalletBalance(AGENT)).isEqualByComparingTo("120.00");
    }

    @Test
    void failedMutation_leavesNoLedgerRow_transactionRollsBack() {
        codWalletService.credit(AGENT, new BigDecimal("10.00"));

        // Insufficient funds: no balance change AND no audit row.
        assertThatThrownBy(() -> codWalletService.debit(AGENT, new BigDecimal("500.00")))
                .isInstanceOf(BusinessException.class);
        assertThat(ledgerRepository.findByAgentIdOrderByIdAsc(AGENT)).hasSize(1);

        // Signum guard: zero/negative credit rejected before any write.
        assertThatThrownBy(() -> codWalletService.credit(AGENT, BigDecimal.ZERO))
                .isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> codWalletService.debit(AGENT, new BigDecimal("-1.00")))
                .isInstanceOf(BusinessException.class);
        assertThat(ledgerRepository.findByAgentIdOrderByIdAsc(AGENT)).hasSize(1);
        assertThat(codWalletService.codWalletBalance(AGENT)).isEqualByComparingTo("10.00");
    }

    @Test
    void getCodWalletBalance_isReadOnly_missingWalletReadsZeroWithoutInsert() {
        assertThat(codWalletService.codWalletBalance(AGENT)).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(walletRepository.findByAgentId(AGENT)).isEmpty();
        assertThat(ledgerRepository.findByAgentIdOrderByIdAsc(AGENT)).isEmpty();
    }

    // ===== earnings guards preserved =====

    @Test
    void duplicateEarning_sameAgentAndOrder_isNoOp() {
        var first = codWalletService.recordEarning(AGENT, 100L, new BigDecimal("49.50"));
        var replay = codWalletService.recordEarning(AGENT, 100L, new BigDecimal("49.50"));

        assertThat(first).isPresent();
        assertThat(replay).isEmpty();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT count(*) FROM rider_earnings WHERE agent_id = " + AGENT, Integer.class))
                .isEqualTo(1);
    }

    @Test
    void earning_overPerDeliveryCap_isRejected() {
        assertThatThrownBy(() -> codWalletService.recordEarning(
                AGENT, 100L, MAX_EARNING_AMOUNT.add(new BigDecimal("0.01"))))
                .isInstanceOf(BusinessException.class);
    }

    // ===== V11 schema guards =====

    @Test
    void v11Migration_addsLedgerTableVersionColumnAndUniqueGuard() {
        List<String> ledgerColumns = jdbcTemplate.queryForList(
                "SELECT column_name FROM information_schema.columns "
                        + "WHERE table_name = 'cod_wallet_ledger'", String.class);
        assertThat(ledgerColumns).contains(
                "id", "agent_id", "order_id", "type", "amount", "balance_after", "created_at");
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

    // ===== concurrency: two threads, no double-spend, ledger consistent =====

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void twoThreadConcurrentDebits_exactlyOneSucceeds_singleLedgerRow() throws Exception {
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
                    codWalletService.debit(AGENT, new BigDecimal("100.00"));
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
        assertThat(succeeded.get()).isEqualTo(1);
        assertThat(insufficient.get()).isEqualTo(1);
        assertThat(codWalletService.codWalletBalance(AGENT)).isEqualByComparingTo(BigDecimal.ZERO);
        // Exactly one DEBIT audit row for exactly one committed mutation.
        assertThat(jdbcTemplate.queryForObject(
                "SELECT count(*) FROM cod_wallet_ledger WHERE type = 'DEBIT'", Integer.class))
                .isEqualTo(1);
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void twoThreadConcurrentCredits_noLostCredit_ledgerChainAgrees() throws Exception {
        codWalletService.credit(AGENT, new BigDecimal("50.00"));

        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch go = new CountDownLatch(1);
        CopyOnWriteArrayList<Throwable> unexpected = new CopyOnWriteArrayList<>();
        for (int t = 0; t < 2; t++) {
            pool.submit(() -> {
                try {
                    go.await(10, TimeUnit.SECONDS);
                    codWalletService.credit(AGENT, new BigDecimal("25.00"));
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
        assertThat(codWalletService.codWalletBalance(AGENT)).isEqualByComparingTo("100.00");
        assertThat(jdbcTemplate.queryForObject(
                "SELECT count(*) FROM cod_wallet_ledger WHERE type = 'CREDIT'", Integer.class))
                .isEqualTo(3);
        // @Version bumped once per locked update, never rejected the path.
        assertThat(jdbcTemplate.queryForObject(
                "SELECT version FROM agent_cod_wallets WHERE agent_id = " + AGENT, Long.class))
                .isEqualTo(3L);
    }
}
