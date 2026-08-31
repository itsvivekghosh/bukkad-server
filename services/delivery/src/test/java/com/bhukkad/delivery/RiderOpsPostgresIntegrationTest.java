package com.bhukkad.delivery;

import com.bhukkad.delivery.domain.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Validates Priority 5 delivery depth (rider location, COD wallet, batches).
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class RiderOpsPostgresIntegrationTest extends AbstractDeliveryPostgresTest {

    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private RiderLocationUpdateRepository locationRepository;
    @Autowired private AgentCodWalletRepository codWalletRepository;
    @Autowired private RiderDeliveryBatchRepository batchRepository;
    @Autowired private RiderDeliveryBatchOrderRepository batchOrderRepository;
    @Autowired private DeliveryAgentRepository agentRepository;

    @BeforeEach
    void clean() {
        jdbcTemplate.update("DELETE FROM rider_delivery_batch_orders");
        jdbcTemplate.update("DELETE FROM rider_delivery_batches");
        jdbcTemplate.update("DELETE FROM agent_cod_wallets");
        jdbcTemplate.update("DELETE FROM rider_location_updates");
        jdbcTemplate.update("DELETE FROM order_delivery_proofs");
        jdbcTemplate.update("DELETE FROM rider_earnings");
        jdbcTemplate.update("DELETE FROM agent_shifts");
        jdbcTemplate.update("DELETE FROM zone_surge_rules");
        jdbcTemplate.update("DELETE FROM delivery_zones");
        jdbcTemplate.update("DELETE FROM city_configs");
        jdbcTemplate.update("DELETE FROM delivery_assignments");
        jdbcTemplate.update("DELETE FROM delivery_agents");
    }

    private DeliveryAgent agent() {
        DeliveryAgent a = new DeliveryAgent();
        a.setName("Rider A");
        a.setIsActive(true);
        return agentRepository.saveAndFlush(a);
    }

    @Test
    void migration_appliedV4() {
        Integer tables = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM information_schema.tables WHERE table_schema = current_schema() " +
                        "AND table_name IN ('rider_location_updates','agent_cod_wallets','rider_delivery_batches','rider_delivery_batch_orders')",
                Integer.class);
        assertThat(tables).isEqualTo(4);
    }

    @Test
    void riderLocationPersists() {
        DeliveryAgent saved = agent();
        RiderLocationUpdate update = new RiderLocationUpdate();
        update.setAgentId(saved.getId());
        update.setLatitude(19.0760);
        update.setLongitude(72.8777);
        update.setRecordedAt(LocalDateTime.now());
        locationRepository.saveAndFlush(update);

        assertThat(locationRepository.findByAgentId(saved.getId())).hasSize(1);
    }

    @Test
    void codWalletUniquePerAgent() {
        DeliveryAgent saved = agent();
        AgentCodWallet wallet = new AgentCodWallet();
        wallet.setAgentId(saved.getId());
        wallet.setBalance(new java.math.BigDecimal("100.00"));
        codWalletRepository.saveAndFlush(wallet);

        assertThat(codWalletRepository.findByAgentId(saved.getId())).isPresent();
    }

    @Test
    void batchAndOrderLinksPersist() {
        DeliveryAgent saved = agent();
        RiderDeliveryBatch batch = new RiderDeliveryBatch();
        batch.setAgentId(saved.getId());
        batch.setStatus("ASSIGNED");
        RiderDeliveryBatch savedBatch = batchRepository.saveAndFlush(batch);

        RiderDeliveryBatchOrder link = new RiderDeliveryBatchOrder();
        link.setBatchId(savedBatch.getId());
        link.setOrderId(100L);
        batchOrderRepository.saveAndFlush(link);

        assertThat(batchRepository.findByAgentIdAndStatus(saved.getId(), "ASSIGNED")).hasSize(1);
        assertThat(batchOrderRepository.findByBatchId(savedBatch.getId())).hasSize(1);
    }
}
