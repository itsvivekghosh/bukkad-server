package com.bhukkad.delivery.service;

import com.bhukkad.delivery.GeoMatchingProperties;
import com.bhukkad.delivery.domain.AgentActiveLoad;
import com.bhukkad.delivery.domain.DeliveryAgent;
import com.bhukkad.delivery.domain.DeliveryAgentRepository;
import com.bhukkad.delivery.domain.DeliveryAssignmentRepository;
import com.bhukkad.delivery.domain.RiderLocationUpdate;
import com.bhukkad.delivery.domain.RiderLocationUpdateRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * ADR-003 nearest-ACTIVE-rider matching. In-memory Haversine over a bounded
 * candidate set (the ADR's "no PostGIS" option): each active agent's newest
 * fresh {@code rider_location_updates} row comes back from one indexed query,
 * riders at the {@code maxActiveAssignments} cap are dropped via one grouped
 * count in the same transaction, and the closest survivor wins.
 *
 * <p>Returns empty whenever geo-matching must not alter behavior: flag off,
 * no anchor coordinates (the mesh {@code assign(orderId)} call site has none —
 * see {@code DeliveryService.assign} javadoc), or no eligible candidate. The
 * caller keeps {@code findFirstByIsActiveTrue} as the documented fallback,
 * and the conditional {@code INSERT ... ON CONFLICT (order_id)} stays the
 * only double-claim arbiter.</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
@EnableConfigurationProperties(GeoMatchingProperties.class)
public class RiderProximityMatcher {

    private final GeoMatchingProperties properties;
    private final RiderLocationUpdateRepository locationRepository;
    private final DeliveryAssignmentRepository assignmentRepository;
    private final DeliveryAgentRepository agentRepository;
    private final DistanceCalculator haversine = new HaversineDistanceCalculator();

    /**
     * @param anchorLat anchor the rider should be closest to (e.g. pickup spot)
     * @return the nearest active, fresh, under-cap rider, or empty when
     *         matching is unavailable and the caller must use the legacy pick
     */
    public Optional<DeliveryAgent> nearestEligible(Double anchorLat, Double anchorLng) {
        if (!properties.isEnabled() || anchorLat == null || anchorLng == null) {
            return Optional.empty();
        }
        LocalDateTime since = LocalDateTime.now().minusMinutes(properties.getLocationFreshnessMinutes());
        List<RiderLocationUpdate> candidates =
                locationRepository.findActiveAgentsLastPositions(since, properties.getCandidateLimit());
        if (candidates.isEmpty()) {
            log.debug("GEO_MATCH_NO_CANDIDATES | anchor=({},{})", anchorLat, anchorLng);
            return Optional.empty();
        }

        Map<Long, RiderLocationUpdate> byAgent = new LinkedHashMap<>();
        for (RiderLocationUpdate pos : candidates) {
            byAgent.putIfAbsent(pos.getAgentId(), pos); // dedupe equal-max timestamps
        }

        Collection<Long> agentIds = byAgent.keySet();
        Map<Long, Long> loads = new java.util.HashMap<>();
        for (AgentActiveLoad load
                : assignmentRepository.countActiveLoadByAgentIds(agentIds)) {
            loads.put(load.agentId(), load.activeCount());
        }

        int cap = properties.getMaxActiveAssignments();
        Long bestAgentId = null;
        double bestKm = Double.MAX_VALUE;
        for (Long agentId : agentIds) {
            if (loads.getOrDefault(agentId, 0L) >= cap) {
                continue; // at capacity — never starve others into over-load
            }
            RiderLocationUpdate pos = byAgent.get(agentId);
            double km = haversine.distanceKm(anchorLat, anchorLng,
                    pos.getLatitude(), pos.getLongitude());
            if (km < bestKm) {
                bestKm = km;
                bestAgentId = agentId;
            }
        }
        if (bestAgentId == null) {
            log.debug("GEO_MATCH_ALL_UNELIGIBLE | freshRiders={} | cap={}", agentIds.size(), cap);
            return Optional.empty();
        }
        double chosenKm = bestKm;
        Long chosenAgentId = bestAgentId;
        Optional<DeliveryAgent> agent = agentRepository.findById(chosenAgentId)
                .filter(a -> Boolean.TRUE.equals(a.getIsActive()));
        if (agent.isPresent()) {
            log.debug("GEO_MATCH_PICK | agentId={} | km={}", chosenAgentId, chosenKm);
        }
        return agent;
    }
}
