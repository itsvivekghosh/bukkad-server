package com.bhukkad.delivery;

import com.bhukkad.delivery.domain.DeliveryAgent;
import com.bhukkad.delivery.domain.DeliveryAgentRepository;
import com.bhukkad.delivery.domain.DeliveryAssignment;
import com.bhukkad.delivery.domain.DeliveryAssignmentRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class DeliveryRepositoryPostgresIntegrationTest extends AbstractDeliveryPostgresTest {

    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private DeliveryAgentRepository agentRepository;
    @Autowired private DeliveryAssignmentRepository assignmentRepository;

    @BeforeEach
    void clean() {
        jdbcTemplate.update("DELETE FROM delivery_assignments");
        jdbcTemplate.update("DELETE FROM delivery_agents");
    }

    @Test
    void migration_applied() {
        Integer tables = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM information_schema.tables WHERE table_schema = current_schema() " +
                        "AND table_name IN ('delivery_agents','delivery_assignments')", Integer.class);
        assertThat(tables).isEqualTo(2);
    }

    @Test
    void activeAgentPickFirst() {
        DeliveryAgent a1 = new DeliveryAgent(); a1.setName("R1"); a1.setIsActive(true);
        DeliveryAgent a2 = new DeliveryAgent(); a2.setName("R2"); a2.setIsActive(true);
        agentRepository.save(a1);
        agentRepository.save(a2);

        assertThat(agentRepository.findFirstByIsActiveTrue()).isPresent();
    }

    @Test
    void assignmentFindByOrder() {
        DeliveryAgent a = new DeliveryAgent(); a.setName("R"); a.setIsActive(true);
        DeliveryAgent saved = agentRepository.saveAndFlush(a);
        DeliveryAssignment d = new DeliveryAssignment();
        d.setOrderId(3L); d.setAgentId(saved.getId()); d.setStatus(DeliveryAssignment.STATUS_ASSIGNED);
        d.setAssignedAt(LocalDateTime.now());
        assignmentRepository.saveAndFlush(d);

        assertThat(assignmentRepository.findByOrderId(3L)).isPresent();
    }
}
