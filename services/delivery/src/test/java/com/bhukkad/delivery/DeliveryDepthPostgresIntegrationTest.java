package com.bhukkad.delivery;

import com.bhukkad.delivery.domain.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.LocalTime;

import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class DeliveryDepthPostgresIntegrationTest extends AbstractDeliveryPostgresTest {

    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private DeliveryZoneRepository zoneRepository;
    @Autowired private CityConfigRepository cityConfigRepository;
    @Autowired private ZoneSurgeRuleRepository surgeRepository;
    @Autowired private AgentShiftRepository shiftRepository;
    @Autowired private RiderEarningRepository earningRepository;
    @Autowired private OrderDeliveryProofRepository proofRepository;
    @Autowired private DeliveryAgentRepository agentRepository;

    @BeforeEach
    void clean() {
        jdbcTemplate.update("DELETE FROM order_delivery_proofs");
        jdbcTemplate.update("DELETE FROM rider_earnings");
        jdbcTemplate.update("DELETE FROM agent_shifts");
        jdbcTemplate.update("DELETE FROM zone_surge_rules");
        jdbcTemplate.update("DELETE FROM delivery_zones");
        jdbcTemplate.update("DELETE FROM city_configs");
        jdbcTemplate.update("DELETE FROM delivery_assignments");
        jdbcTemplate.update("DELETE FROM delivery_agents");
    }

    @Test
    void migration_appliedV3Tables() {
        Integer tables = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM information_schema.tables WHERE table_schema = current_schema() " +
                        "AND table_name IN ('delivery_zones','city_configs','zone_surge_rules','agent_shifts','rider_earnings','order_delivery_proofs')",
                Integer.class);
        assertThat(tables).isEqualTo(6);
    }

    @Test
    void zoneAndSurgePersist() {
        DeliveryZone zone = new DeliveryZone();
        zone.setName("Downtown");
        zoneRepository.saveAndFlush(zone);

        ZoneSurgeRule surge = new ZoneSurgeRule();
        surge.setZoneId(zone.getId());
        surge.setStartTime(LocalTime.of(18, 0));
        surge.setEndTime(LocalTime.of(22, 0));
        surge.setMultiplier(new BigDecimal("1.50"));
        surge.setActive(true);
        surgeRepository.saveAndFlush(surge);

        assertThat(surgeRepository.findByZoneIdAndActiveTrue(zone.getId())).hasSize(1);
    }

    @Test
    void cityConfigPersists() {
        CityConfig config = new CityConfig();
        config.setCityName("Mumbai");
        config.setCurrency("INR");
        config.setTimezone("Asia/Kolkata");
        config.setCreatedAt(LocalDateTime.now());
        cityConfigRepository.saveAndFlush(config);

        assertThat(cityConfigRepository.findAll()).hasSize(1);
    }

    @Test
    void agentShiftAndRiderEarning() {
        DeliveryAgent agent = new DeliveryAgent();
        agent.setName("Rider A");
        agent.setIsActive(true);
        agentRepository.saveAndFlush(agent);

        AgentShift shift = new AgentShift();
        shift.setAgentId(agent.getId());
        shift.setStartTime(LocalDateTime.now());
        shift.setStatus("ACTIVE");
        shiftRepository.saveAndFlush(shift);

        RiderEarning earning = new RiderEarning();
        earning.setAgentId(agent.getId());
        earning.setOrderId(1L);
        earning.setAmount(new BigDecimal("50.00"));
        earning.setStatus("PENDING");
        earning.setCreatedAt(LocalDateTime.now());
        earningRepository.saveAndFlush(earning);

        assertThat(earningRepository.findByAgentId(agent.getId())).hasSize(1);
    }

    @Test
    void deliveryProofPersists() {
        OrderDeliveryProof proof = new OrderDeliveryProof();
        proof.setOrderId(1L);
        proof.setPhotoUrl("/photos/pic.jpg");
        proof.setSignature("customer-sig");
        proof.setCreatedAt(LocalDateTime.now());
        proofRepository.saveAndFlush(proof);

        assertThat(proofRepository.findAll()).hasSize(1);
    }
}