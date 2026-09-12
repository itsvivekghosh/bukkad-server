package com.bhukkad.admin;

import com.bhukkad.admin.domain.entity.AuditEvent;
import com.bhukkad.admin.domain.repository.AuditEventRepository;
import com.bhukkad.admin.domain.entity.FraudEvent;
import com.bhukkad.admin.domain.repository.FraudEventRepository;
import com.bhukkad.admin.domain.entity.RestaurantOrderStat;
import com.bhukkad.admin.domain.repository.RestaurantOrderStatRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class AdminRepositoryPostgresIntegrationTest extends AbstractAdminPostgresTest {

    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private AuditEventRepository auditRepository;
    @Autowired private FraudEventRepository fraudRepository;
    @Autowired private RestaurantOrderStatRepository statRepository;

    @BeforeEach
    void clean() {
        jdbcTemplate.update("DELETE FROM restaurant_order_stats");
        jdbcTemplate.update("DELETE FROM fraud_events");
        jdbcTemplate.update("DELETE FROM audit_events");
    }

    @Test
    void migration_applied() {
        Integer tables = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM information_schema.tables WHERE table_schema = current_schema() " +
                        "AND table_name IN ('audit_events','fraud_events','restaurant_order_stats')", Integer.class);
        assertThat(tables).isEqualTo(3);
    }

    @Test
    void auditTrailByEntity() {
        AuditEvent e = new AuditEvent();
        e.setEntityType("ORDER");
        e.setEntityId(1L);
        e.setAction("UPDATE");
        auditRepository.saveAndFlush(e);
        assertThat(auditRepository.findByEntityTypeAndEntityId("ORDER", 1L)).hasSize(1);
    }

    @Test
    void fraudByStatus() {
        FraudEvent f = new FraudEvent();
        f.setCustomerId(1L);
        f.setRule("velocity");
        f.setSeverity("HIGH");
        f.setStatus(FraudEvent.STATUS_REVIEW);
        fraudRepository.saveAndFlush(f);
        assertThat(fraudRepository.findByStatus("REVIEW")).hasSize(1);
        assertThat(fraudRepository.findByCustomerId(1L)).hasSize(1);
    }

    @Test
    void statsPersist() {
        RestaurantOrderStat s = new RestaurantOrderStat();
        s.setRestaurantId(5L);
        s.setOrderCount(10L);
        s.setRevenue(new BigDecimal("1000.00"));
        statRepository.saveAndFlush(s);
        assertThat(statRepository.findAll()).hasSize(1);
    }
}
