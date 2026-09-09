package com.bhukkad.delivery;

import com.bhukkad.delivery.domain.DeliveryAgent;
import com.bhukkad.delivery.domain.DeliveryAgentRepository;
import com.bhukkad.delivery.domain.RiderLocationUpdate;
import com.bhukkad.delivery.domain.RiderLocationUpdateRepository;
import com.bhukkad.delivery.service.RiderLocationRetentionService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Rider location retention purge on real PostgreSQL: expired pings past the
 * cutoff are deleted in bounded batches (loop drains a backlog, capped per
 * tick), fresh rows survive, a second tick is a no-op, and the batched delete
 * respects the batch size (never a full-table DELETE). The service is built
 * directly against the real repository (no context plumbing needed).
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class RiderLocationRetentionPostgresIntegrationTest extends AbstractDeliveryPostgresTest {

    @Autowired private RiderLocationUpdateRepository locationRepository;
    @Autowired private DeliveryAgentRepository agentRepository;
    @Autowired private JdbcTemplate jdbcTemplate;

    private RiderLocationRetentionProperties properties;
    private RiderLocationRetentionService retentionService;
    private Long seededAgentId;

    @BeforeEach
    void setUp() {
        // FK-safe order (committed rows from sibling @DataJpaTest classes share
        // this container): assignments reference agents.
        jdbcTemplate.update("DELETE FROM delivery_assignments");
        jdbcTemplate.update("DELETE FROM rider_location_updates");
        jdbcTemplate.update("DELETE FROM delivery_agents");
        seededAgentId = null;
        properties = new RiderLocationRetentionProperties();
        properties.setCutoffHours(72);
        properties.setBatchSize(2);       // force the loop to walk several batches
        properties.setMaxBatches(50);
        retentionService = new RiderLocationRetentionService(locationRepository, properties);
    }

    private void ping(LocalDateTime recordedAt) {
        if (seededAgentId == null) {
            DeliveryAgent agent = new DeliveryAgent();
            agent.setName("Rider Ping");
            agent.setIsActive(true);
            seededAgentId = agentRepository.saveAndFlush(agent).getId();
        }
        RiderLocationUpdate location = new RiderLocationUpdate();
        location.setAgentId(seededAgentId);
        location.setLatitude(19.0);
        location.setLongitude(72.8);
        location.setRecordedAt(recordedAt);
        locationRepository.saveAndFlush(location);
    }

    @Test
    void purge_deletesExpiredKeepsFreshAndLoopsBatches() {
        LocalDateTime stale = LocalDateTime.now().minusHours(80);
        for (int i = 0; i < 5; i++) {
            ping(stale.minusSeconds(i));
        }
        for (int i = 0; i < 3; i++) {
            ping(LocalDateTime.now().minusMinutes(i));
        }

        int deleted = retentionService.purgeExpired();

        assertThat(deleted).isEqualTo(5); // drained via 2 + 2 + 1 chunks
        assertThat(jdbcTemplate.queryForObject(
                "SELECT count(*) FROM rider_location_updates", Integer.class)).isEqualTo(3);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT count(*) FROM rider_location_updates "
                        + "WHERE recorded_at < now() - interval '72 hours'",
                Integer.class)).isZero();
    }

    @Test
    void purge_secondTickIsNoOp() {
        assertThat(retentionService.purgeExpired()).isZero();
    }

    @Test
    void purge_stopsAtMaxBatches_andOnlyDeletesStaleRows() {
        LocalDateTime stale = LocalDateTime.now().minusHours(90);
        for (int i = 0; i < 7; i++) {
            ping(stale.minusSeconds(i));
        }
        properties.setBatchSize(2);
        properties.setMaxBatches(2); // 4 of the 7 stale rows this tick, rest by design

        int deleted = retentionService.purgeExpired();

        assertThat(deleted).isEqualTo(4);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT count(*) FROM rider_location_updates", Integer.class)).isEqualTo(3);
    }
}
