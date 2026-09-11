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
import java.util.List;

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

        assertThat(agentRepository.findByIsActiveTrueOrderByIdAsc())
                .extracting(DeliveryAgent::getName)
                .containsExactly("R1", "R2");
    }

    @Test
    void activeLoad_capConditionalUpdateArbitrates() {
        DeliveryAgent a = new DeliveryAgent(); a.setName("R"); a.setIsActive(true);
        DeliveryAgent saved = agentRepository.saveAndFlush(a);

        // Two admissions within cap=1: only the first wins.
        assertThat(agentRepository.incrementActiveLoadWithinCap(saved.getId(), 1)).isEqualTo(1);
        assertThat(agentRepository.incrementActiveLoadWithinCap(saved.getId(), 1)).isZero();

        // Raising the cap admits again; decrement is bounded at zero.
        assertThat(agentRepository.incrementActiveLoadWithinCap(saved.getId(), 2)).isEqualTo(1);
        assertThat(agentRepository.decrementActiveLoad(saved.getId())).isEqualTo(1);
        assertThat(agentRepository.decrementActiveLoad(saved.getId())).isEqualTo(1);
        assertThat(agentRepository.decrementActiveLoad(saved.getId())).isZero();
        assertThat(agentRepository.findById(saved.getId()).orElseThrow().getActiveLoad()).isZero();
    }

    @Test
    void positionedCandidates_rankByReferenceDistance_thenRecency() {
        LocalDateTime now = LocalDateTime.now();
        // R1: ~1.5 km north of the Mumbai reference point, pinged second.
        DeliveryAgent near = new DeliveryAgent(); near.setName("Near"); near.setIsActive(true);
        DeliveryAgent savedNear = agentRepository.saveAndFlush(near);
        locationUpdate(savedNear.getId(), 19.0896, 72.8656, now.minusSeconds(10));
        // R2: ~150 km away (Pune), pinged last (recency would prefer it).
        DeliveryAgent far = new DeliveryAgent(); far.setName("Far"); far.setIsActive(true);
        DeliveryAgent savedFar = agentRepository.saveAndFlush(far);
        locationUpdate(savedFar.getId(), 18.5204, 73.8567, now);
        // R3: active but never pinged — must not appear.
        DeliveryAgent silent = new DeliveryAgent(); silent.setName("Silent"); silent.setIsActive(true);
        agentRepository.saveAndFlush(silent);

        // With a reference point: distance ranking beats recency ranking.
        List<DeliveryAgentRepository.RiderCandidate> ranked = agentRepository
                .findPositionedCandidates(19.0760, 72.8777, now.minusMinutes(15), 20);
        assertThat(ranked).extracting(DeliveryAgentRepository.RiderCandidate::getId)
                .containsExactly(savedNear.getId(), savedFar.getId());
        assertThat(ranked.get(0).getDistanceKm()).isLessThan(ranked.get(1).getDistanceKm());

        // Without a reference point: recency ranking (live-GPS riders first).
        List<DeliveryAgentRepository.RiderCandidate> recency = agentRepository
                .findPositionedCandidates(null, null, now.minusMinutes(15), 20);
        assertThat(recency).extracting(DeliveryAgentRepository.RiderCandidate::getId)
                .containsExactly(savedFar.getId(), savedNear.getId());
    }

    @Test
    void positionedCandidates_stalePositionOutsideFreshnessWindowExcluded() {
        LocalDateTime now = LocalDateTime.now();
        DeliveryAgent a = new DeliveryAgent(); a.setName("Stale"); a.setIsActive(true);
        DeliveryAgent saved = agentRepository.saveAndFlush(a);
        locationUpdate(saved.getId(), 19.0760, 72.8777, now.minusMinutes(30));

        assertThat(agentRepository.findPositionedCandidates(null, null,
                now.minusMinutes(15), 20)).isEmpty();
    }

    private void locationUpdate(Long agentId, double lat, double lng, LocalDateTime recordedAt) {
        jdbcTemplate.update(
                "INSERT INTO rider_location_updates (agent_id, latitude, longitude, recorded_at, created_at) "
                        + "VALUES (?, ?, ?, ?, ?)",
                agentId, lat, lng, recordedAt, LocalDateTime.now());
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
