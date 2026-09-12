package com.bhukkad.delivery.domain;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

public interface RiderLocationUpdateRepository extends JpaRepository<RiderLocationUpdate, Long> {
    List<RiderLocationUpdate> findByAgentId(Long agentId);

    /** Latest recorded position of one agent (ADR-003 freshness/ETA input). */
    RiderLocationUpdate findTopByAgentIdOrderByRecordedAtDesc(Long agentId);

    /**
     * ADR-003 proximity candidates: each ACTIVE agent's LAST recorded position,
     * kept only while fresh (>= {@code since}). The correlated MAX probe and
     * the {@code da.is_active} join ride the existing
     * {@code idx_rider_location_agent (agent_id, recorded_at)} index — no
     * PostGIS, no new columns; {@code LIMIT} bounds the in-memory candidate
     * set as a hostile-pool guard.
     */
    @Query(value = """
            SELECT rlu.* FROM rider_location_updates rlu
            JOIN delivery_agents da ON da.id = rlu.agent_id
            WHERE da.is_active = true
              AND rlu.recorded_at >= :since
              AND rlu.recorded_at = (SELECT MAX(r2.recorded_at)
                                     FROM rider_location_updates r2
                                     WHERE r2.agent_id = rlu.agent_id)
            ORDER BY rlu.agent_id
            LIMIT :limit
            """, nativeQuery = true)
    List<RiderLocationUpdate> findActiveAgentsLastPositions(@Param("since") LocalDateTime since,
                                                            @Param("limit") int limit);

    /**
     * Batched retention delete (PERF-4 §4.3): removes at most {@code limit} of
     * the oldest rows past the cutoff, looping cheap. Backed by the
     * single-column {@code recorded_at} index added with this purge (the
     * existing {@code idx_rider_location_agent} leads with agent_id and cannot
     * serve a cutoff-only predicate).
     */
    @Modifying
    @Query(value = "DELETE FROM rider_location_updates WHERE id IN ("
            + "SELECT id FROM rider_location_updates "
            + "WHERE recorded_at < :cutoff ORDER BY recorded_at LIMIT :limit)",
            nativeQuery = true)
    int deleteOldestBefore(@Param("cutoff") LocalDateTime cutoff, @Param("limit") int limit);
}
