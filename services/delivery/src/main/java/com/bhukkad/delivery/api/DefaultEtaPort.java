package com.bhukkad.delivery.api;

import com.bhukkad.delivery.RoadDistanceService;
import com.bhukkad.delivery.domain.DeliveryAssignmentRepository;
import com.bhukkad.delivery.domain.RiderLocationUpdate;
import com.bhukkad.delivery.domain.RiderLocationUpdateRepository;
import com.bhukkad.delivery.service.EtaDestinationResolver;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.Optional;

/**
 * Live-ETA port backed by the delivery module's own data: the assigned
 * rider's last-known GPS position plus the order's destination (when a
 * {@link EtaDestinationResolver} bean supplies it), turned into road minutes
 * via {@link RoadDistanceService} (OSRM when enabled, haversine fallback
 * otherwise — the RestTemplate client stays untouched per ADR-003).
 *
 * <p>Degradation is deliberate and total: when {@code app.eta.enabled} is
 * false (default) no {@code EtaService} bean exists, when the order is not
 * assigned, when the rider has no fresh position, or when the destination is
 * unknown, the port answers empty — the pre-existing behaviour. The live
 * streaming module ({@code OrderLiveUpdateBroadcaster}) is the wired caller:
 * every rider-location broadcast for a tracked (assigned) order asks this
 * port for the ETA snapshot.</p>
 */
@Slf4j
@Service
public class DefaultEtaPort implements EtaPort {

    /** Fixed restaurant prep/handling time (monolith ETA semantics). */
    private static final double PREP_MINUTES = 15.0;
    /** Floor so a rider already at the destination still gets a sane ETA. */
    private static final int MIN_TRAVEL_MINUTES = 5;
    /** Confidence band around the point estimate (monolith V14 semantics). */
    private static final int CONFIDENCE_BAND_MINUTES = 5;

    private final ObjectProvider<com.bhukkad.delivery.service.EtaService> etaService;
    private final DeliveryAssignmentRepository assignmentRepository;
    private final RiderLocationUpdateRepository locationRepository;
    private final RoadDistanceService roadDistanceService;
    private final ObjectProvider<EtaDestinationResolver> destinations;

    public DefaultEtaPort(ObjectProvider<com.bhukkad.delivery.service.EtaService> etaService,
                          DeliveryAssignmentRepository assignmentRepository,
                          RiderLocationUpdateRepository locationRepository,
                          RoadDistanceService roadDistanceService,
                          ObjectProvider<EtaDestinationResolver> destinations) {
        this.etaService = etaService;
        this.assignmentRepository = assignmentRepository;
        this.locationRepository = locationRepository;
        this.roadDistanceService = roadDistanceService;
        this.destinations = destinations;
    }

    @Override
    public Optional<EtaSnapshot> computeEta(Long orderId) {
        // Flag off (default): no EtaService bean → unchanged empty-port answer.
        if (orderId == null || etaService.getIfAvailable() == null) {
            return Optional.empty();
        }
        return assignmentRepository.findByOrderId(orderId)
                .flatMap(assignment -> locationRepository
                        .findFirstByAgentIdOrderByRecordedAtDesc(assignment.getAgentId()))
                .flatMap(position -> snapshotFor(orderId, position));
    }

    private Optional<EtaSnapshot> snapshotFor(Long orderId, RiderLocationUpdate riderPosition) {
        Optional<EtaDestinationResolver.Coordinates> destination =
                destinations.orderedStream().findFirst().flatMap(resolver -> resolver.destinationOf(orderId));
        if (destination.isEmpty()) {
            // Order coordinates are not known to the delivery service yet
            // (ADR-003 order-side plumbing pending) — answer empty, never guess.
            log.debug("ETA_DESTINATION_UNKNOWN | orderId={}", orderId);
            return Optional.empty();
        }
        RoadDistanceService.RoadRoute route = roadDistanceService.route(
                riderPosition.getLatitude(), riderPosition.getLongitude(),
                destination.get().latitude(), destination.get().longitude());
        double trafficFactor = trafficFactor(LocalDateTime.now().getHour());
        int minutes = scaleMinutes(
                Math.max(MIN_TRAVEL_MINUTES, (int) Math.ceil(route.durationMin())) + (int) PREP_MINUTES,
                trafficFactor);
        String factors = String.format("traffic=%.2f,osrm=%s,prep=%.0f",
                trafficFactor, route.fromOsrm(), PREP_MINUTES);
        return Optional.of(new EtaSnapshot(
                minutes,
                LocalDateTime.now().plusMinutes(minutes),
                Math.max(0, minutes - CONFIDENCE_BAND_MINUTES),
                minutes + CONFIDENCE_BAND_MINUTES,
                trafficFactor,
                1.0,
                factors));
    }

    /** Peak-hour traffic heuristic ported from the monolith OrderEtaService. */
    static double trafficFactor(int hour) {
        if (hour >= 12 && hour < 14) return 1.15;
        if (hour >= 19 && hour < 22) return 1.25;
        if (hour >= 7 && hour < 10) return 1.1;
        return 1.0;
    }

    private static int scaleMinutes(int base, double factor) {
        return (int) Math.ceil(base * factor);
    }
}
