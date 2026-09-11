package com.bhukkad.delivery;

import com.bhukkad.delivery.domain.AgentActiveLoad;
import com.bhukkad.delivery.domain.DeliveryAgent;
import com.bhukkad.delivery.domain.DeliveryAgentRepository;
import com.bhukkad.delivery.domain.DeliveryAssignment;
import com.bhukkad.delivery.domain.DeliveryAssignmentRepository;
import com.bhukkad.delivery.domain.RiderLocationUpdate;
import com.bhukkad.delivery.domain.RiderLocationUpdateRepository;
import com.bhukkad.delivery.service.RiderProximityMatcher;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * P3 / ADR-003 matching on REAL PostgreSQL: freshness cutoff and latest-row
 * selection run inside {@code rider_location_updates} via the exact
 * {@code (agent_id, recorded_at)} index path (no PostGIS), and the grouped
 * capacity count mirrors {@code markDeliveredIfOpen}'s {@code UPPER(status)}
 * predicate over mixed-case legacy states.
 */
@DataJpaTest(properties = {
        "app.delivery.geo-matching.enabled=true",
        "app.delivery.geo-matching.location-freshness-minutes=10",
        "app.delivery.geo-matching.max-active-assignments=2",
        "app.delivery.geo-matching.candidate-limit=50"})
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import(RiderProximityMatcher.class)
class DeliveryGeoMatchingPostgresTest extends AbstractDeliveryPostgresTest {

    @Autowired private RiderProximityMatcher matcher;
    @Autowired private DeliveryAgentRepository agentRepository;
    @Autowired private RiderLocationUpdateRepository locationRepository;
    @Autowired private DeliveryAssignmentRepository assignmentRepository;
    @Autowired private JdbcTemplate jdbcTemplate;

    // Anchor = Mumbai CST; riders placed east/west of it.
    private static final double ANCHOR_LAT = 19.0760;
    private static final double ANCHOR_LNG = 72.8777;

    @BeforeEach
    void clean() {
        jdbcTemplate.update("DELETE FROM rider_location_updates");
        jdbcTemplate.update("DELETE FROM delivery_assignments");
        jdbcTemplate.update("DELETE FROM delivery_agents");
    }

    private Long agent(String name, boolean active) {
        DeliveryAgent a = new DeliveryAgent();
        a.setName(name);
        a.setIsActive(active);
        return agentRepository.saveAndFlush(a).getId();
    }

    private void location(Long agentId, double lat, double lng, LocalDateTime at) {
        RiderLocationUpdate u = new RiderLocationUpdate();
        u.setAgentId(agentId);
        u.setLatitude(lat);
        u.setLongitude(lng);
        u.setRecordedAt(at);
        locationRepository.saveAndFlush(u);
    }

    private void assignment(Long orderId, Long agentId, String status) {
        jdbcTemplate.update("INSERT INTO delivery_assignments "
                        + "(order_id, agent_id, status, assigned_at, created_at) "
                        + "VALUES (?, ?, ?, now(), now())", orderId, agentId, status);
    }

    @Test
    void nearestWins_amongThreeRealRiders_byLatestPositions() {
        Long near = agent("near", true);
        Long mid = agent("mid", true);
        Long far = agent("far", true);
        var now = LocalDateTime.now();
        // Each rider has an older position too — only the LAST one counts.
        location(near, ANCHOR_LAT + 5, ANCHOR_LNG + 5, now.minusMinutes(3));
        location(near, ANCHOR_LAT + 0.02, ANCHOR_LNG, now.minusMinutes(1));
        location(mid, ANCHOR_LAT + 0.1, ANCHOR_LNG, now.minusMinutes(2));
        location(far, ANCHOR_LAT + 1.0, ANCHOR_LNG, now.minusMinutes(2));

        assertThat(matcher.nearestEligible(ANCHOR_LAT, ANCHOR_LNG))
                .map(DeliveryAgent::getId).contains(near);
    }

    @Test
    void staleLatestPosition_riderExcluded_evenWithOlderFreshLookingRows() {
        Long stale = agent("stale", true);
        var now = LocalDateTime.now();
        location(stale, ANCHOR_LAT + 0.01, ANCHOR_LNG, now.minusDays(1));
        location(stale, ANCHOR_LAT + 0.02, ANCHOR_LNG, now.minusDays(2)); // older, "inside" nothing

        assertThat(matcher.nearestEligible(ANCHOR_LAT, ANCHOR_LNG)).isEmpty();
    }

    @Test
    void inactiveAgentExcluded_activeNearestWins() {
        Long inactiveNear = agent("inactive-near", false);
        Long activeFar = agent("active-far", true);
        var now = LocalDateTime.now();
        location(inactiveNear, ANCHOR_LAT + 0.01, ANCHOR_LNG, now.minusMinutes(1));
        location(activeFar, ANCHOR_LAT + 0.5, ANCHOR_LNG, now.minusMinutes(1));

        assertThat(matcher.nearestEligible(ANCHOR_LAT, ANCHOR_LNG))
                .map(DeliveryAgent::getId).contains(activeFar);
    }

    @Test
    void capacityFallback_nearestAtCapYieldsToNextNearest() {
        Long loaded = agent("loaded", true);
        Long spare = agent("spare", true);
        var now = LocalDateTime.now();
        location(loaded, ANCHOR_LAT + 0.01, ANCHOR_LNG, now.minusMinutes(1));
        location(spare, ANCHOR_LAT + 0.2, ANCHOR_LNG, now.minusMinutes(1));
        // cap = 2: two OPEN rows for `loaded` (one DELIVERED must NOT count).
        assignment(101L, loaded, DeliveryAssignment.STATUS_ASSIGNED);
        assignment(102L, loaded, "accepted");
        assignment(103L, loaded, DeliveryAssignment.STATUS_DELIVERED);

        List<AgentActiveLoad> loads =
                assignmentRepository.countActiveLoadByAgentIds(List.of(loaded, spare));
        assertThat(loads).anySatisfy(l -> {
            assertThat(l.agentId()).isEqualTo(loaded);
            assertThat(l.activeCount()).isEqualTo(2); // DELIVERED excluded, lowercase 'accepted' counted
        });

        assertThat(matcher.nearestEligible(ANCHOR_LAT, ANCHOR_LNG))
                .map(DeliveryAgent::getId).contains(spare);
    }
}
