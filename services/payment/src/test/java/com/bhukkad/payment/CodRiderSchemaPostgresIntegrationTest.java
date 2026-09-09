package com.bhukkad.payment;

import com.bhukkad.payment.domain.AgentCodWallet;
import com.bhukkad.payment.domain.AgentCodWalletRepository;
import com.bhukkad.payment.domain.RiderEarning;
import com.bhukkad.payment.domain.RiderEarningRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Audit C-5/H-5: the flyway PG schema must actually carry every column the
 * AgentCodWallet / RiderEarning entities and the internal delivery surface
 * map. Runs the real migrations (V1+V2) on PostgreSQL.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class CodRiderSchemaPostgresIntegrationTest extends AbstractPaymentPostgresTest {

    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private AgentCodWalletRepository codWalletRepository;
    @Autowired private RiderEarningRepository earningRepository;

    @BeforeEach
    void clean() {
        jdbcTemplate.update("DELETE FROM rider_earnings");
        jdbcTemplate.update("DELETE FROM agent_cod_wallets");
    }

    @Test
    void agentCodWalletCarriesBalanceStatusAndAuditColumns() {
        assertThat(columnsOf("agent_cod_wallets"))
                .contains("balance", "status", "updated_at", "agent_id", "created_at");
    }

    @Test
    void riderEarningsCarriesStatusPaidAtAndNumericMoneyColumn() {
        assertThat(columnsOf("rider_earnings"))
                .contains("status", "paid_at", "agent_id", "order_id", "amount");
        String amountType = jdbcTemplate.queryForObject(
                "SELECT data_type FROM information_schema.columns " +
                        "WHERE table_name = 'rider_earnings' AND column_name = 'amount'",
                String.class);
        assertThat(amountType).isEqualTo("numeric");
    }

    @Test
    void riderEarnings_uniqueIndexEnforcesOneRowPerAgentOrder() {
        Integer idx = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM pg_indexes WHERE tablename = 'rider_earnings' " +
                        "AND indexname = 'uq_rider_earnings_agent_order'",
                Integer.class);
        assertThat(idx).isEqualTo(1);

        earningRepository.saveAndFlush(earning(5L, 100L));
        // Distinct orders for the same rider remain payable.
        earningRepository.saveAndFlush(earning(5L, 101L));
        assertThat(earningRepository.findByAgentId(5L)).hasSize(2);

        // Last statement: the violation aborts the transaction (PG semantics).
        assertThatThrownBy(() -> earningRepository.saveAndFlush(earning(5L, 100L)))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void codWalletRoundTripsMappedColumns() {
        AgentCodWallet wallet = new AgentCodWallet();
        wallet.setAgentId(9L);
        wallet.setBalance(new BigDecimal("500.00"));
        codWalletRepository.saveAndFlush(wallet);

        AgentCodWallet locked = codWalletRepository.findByAgentIdForUpdate(9L).orElseThrow();
        assertThat(locked.getBalance()).isEqualByComparingTo("500.00");
        assertThat(locked.getUpdatedAt()).isNotNull();
        assertThat(locked.getCreatedAt()).isNotNull();
    }

    private java.util.List<String> columnsOf(String table) {
        return jdbcTemplate.queryForList(
                "SELECT column_name FROM information_schema.columns " +
                        "WHERE table_schema = current_schema() AND table_name = ?",
                String.class, table);
    }

    private RiderEarning earning(long agentId, long orderId) {
        RiderEarning e = new RiderEarning();
        e.setAgentId(agentId);
        e.setOrderId(orderId);
        e.setAmount(new BigDecimal("49.50"));
        e.setStatus("EARNED");
        return e;
    }
}
