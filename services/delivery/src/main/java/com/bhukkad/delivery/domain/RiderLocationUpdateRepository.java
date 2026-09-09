package com.bhukkad.delivery.domain;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;

public interface RiderLocationUpdateRepository extends JpaRepository<RiderLocationUpdate, Long> {
    List<RiderLocationUpdate> findByAgentId(Long agentId);

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
