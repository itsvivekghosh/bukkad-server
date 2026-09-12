package com.bhukkad.delivery.domain;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

public interface DeliveryAgentRepository extends JpaRepository<DeliveryAgent, Long> {

    /** Legacy fallback pick (ADR-003): the first active agent by id. */
    Optional<DeliveryAgent> findFirstByIsActiveTrue();

    /** Deterministic fallback candidate list (id order = legacy first-agent first). */
    List<DeliveryAgent> findByIsActiveTrueOrderByIdAsc();

    /**
     * Conditional load-cap increment (PERF-4 rider matching, migration V10).
     * 1 row = this dispatcher took the agent within the cap; 0 rows = the
     * agent is at cap (or gone) and the caller must try the next candidate.
     * The predicate is re-evaluated against the latest committed row after a
     * row-lock wait, so racing dispatchers cannot push a rider past the cap.
     */
    @Modifying
    @Query(value = "UPDATE delivery_agents "
            + "SET active_load = active_load + 1, updated_at = now() "
            + "WHERE id = :id AND active_load < :cap", nativeQuery = true)
    int incrementActiveLoadWithinCap(@Param("id") Long id, @Param("cap") int cap);

    /**
     * Lifecycle close for the counter: markDelivered releases one slot.
     * Guarded against going negative so replays of the delivered transition
     * cannot push the counter below zero.
     */
    @Modifying
    @Query(value = "UPDATE delivery_agents "
            + "SET active_load = active_load - 1, updated_at = now() "
            + "WHERE id = :id AND active_load > 0", nativeQuery = true)
    int decrementActiveLoad(@Param("id") Long id);

    /**
     * ADR-003 proximity candidates: active riders with a fresh last-known GPS
     * position (latest {@code rider_location_updates} row per agent within the
     * freshness window), ranked by haversine distance from the dispatch
     * reference point (plain SQL — PostGIS stays deferred per ADR-003).
     * When {@code refLat}/{@code refLng} are null (the order's coordinates are
     * not known to the delivery service yet), candidates rank by position
     * recency only — live-GPS riders first — and the caller still falls back
     * to the legacy first-active-agent pick when this returns empty.
     */
    @Query(value = """
            SELECT a.id AS id,
                   u.latitude AS latitude,
                   u.longitude AS longitude,
                   u.recorded_at AS recordedAt,
                   CASE WHEN :refLat IS NULL OR :refLng IS NULL THEN NULL ELSE
                       (6371.0 * acos(least(1.0, greatest(-1.0,
                           cos(radians(:refLat)) * cos(radians(u.latitude))
                               * cos(radians(u.longitude) - radians(:refLng))
                           + sin(radians(:refLat)) * sin(radians(u.latitude))))))
                   END AS distanceKm
            FROM delivery_agents a
            JOIN LATERAL (
                SELECT r.latitude, r.longitude, r.recorded_at
                FROM rider_location_updates r
                WHERE r.agent_id = a.id AND r.recorded_at >= :cutoff
                ORDER BY r.recorded_at DESC
                LIMIT 1
            ) u ON TRUE
            WHERE a.is_active = TRUE
            ORDER BY distanceKm NULLS LAST, u.recorded_at DESC, a.id ASC
            LIMIT :limit
            """, nativeQuery = true)
    List<RiderCandidate> findPositionedCandidates(@Param("refLat") Double refLat,
                                                  @Param("refLng") Double refLng,
                                                  @Param("cutoff") LocalDateTime cutoff,
                                                  @Param("limit") int limit);

    /**
     * One positioned, active rider candidate (projection over the
     * {@code findPositionedCandidates} native query; alias names bind getters).
     */
    interface RiderCandidate {
        Long getId();

        Double getLatitude();

        Double getLongitude();

        LocalDateTime getRecordedAt();

        Double getDistanceKm();
    }
}
