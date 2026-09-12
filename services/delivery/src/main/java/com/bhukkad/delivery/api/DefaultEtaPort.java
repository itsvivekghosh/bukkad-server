package com.bhukkad.delivery.api;

import com.bhukkad.delivery.DeliveryEtaProperties;
import com.bhukkad.delivery.domain.DeliveryAssignment;
import com.bhukkad.delivery.domain.DeliveryAssignmentRepository;
import com.bhukkad.delivery.domain.RiderLocationUpdate;
import com.bhukkad.delivery.domain.RiderLocationUpdateRepository;
import com.bhukkad.delivery.service.EtaService;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.Locale;
import java.util.Optional;

/**
 * Live-ETA port implementation (P3 / ADR-003).
 *
 * <p>Default behaviour is UNCHANGED: with {@code app.delivery.eta.enabled=false}
 * (shipped default) the gated {@link EtaService} bean does not exist and every
 * call returns {@code Optional.empty()} — the pre-P3 answer, so SSE payloads
 * keep their current shape. The wire type ({@link EtaSnapshot}) never changes.</p>
 *
 * <p>When enabled, the snapshot is produced like the monolith's
 * {@code OrderEtaService.deliveryMinutes} worked: geo-exact (rider→drop-off via
 * {@code EtaService}, which itself degrades to haversine until OSRM ships)
 * whenever a FRESH assigned-rider position and a drop-off are known, else the
 * status+peak-traffic heuristic — the delivery schema stores no order
 * destination, and adding a new order→delivery coordinate hand-off is another
 * batch's data-flow change (the no-arg SSE path is the heuristically-
 * documented degradation, not a silent zero).</p>
 */
@Service
@RequiredArgsConstructor
public class DefaultEtaPort implements EtaPort {

    private final ObjectProvider<EtaService> etaService;
    private final DeliveryEtaProperties etaProperties;
    private final DeliveryAssignmentRepository assignmentRepository;
    private final RiderLocationUpdateRepository locationRepository;

    @Override
    public Optional<EtaSnapshot> computeEta(Long orderId) {
        return computeEta(orderId, null, null);
    }

    /**
     * Geo-exact entry point for callers that hold the drop-off coordinates.
     * The {@link EtaPort} contract callers (live broadcaster) only know the
     * order id, so the no-coordinate overload above stays the mesh default.
     */
    public Optional<EtaSnapshot> computeEta(Long orderId, Double destLat, Double destLng) {
        EtaService eta = etaService.getIfAvailable();
        if (eta == null) {
            return Optional.empty(); // gate off — original empty behavior
        }
        Optional<DeliveryAssignment> assignment = assignmentRepository.findByOrderId(orderId);
        if (assignment.isEmpty()
                || DeliveryAssignment.STATUS_DELIVERED.equalsIgnoreCase(assignment.get().getStatus())) {
            return Optional.empty();
        }

        RiderLocationUpdate rider = freshRiderPosition(assignment.get().getAgentId());
        double traffic = resolveTrafficFactor();
        int minutes;
        String factors;
        boolean geo = rider != null && destLat != null && destLng != null;
        if (geo) {
            minutes = (int) Math.ceil(Math.max(1, eta.etaMinutes(
                    rider.getLatitude(), rider.getLongitude(), destLat, destLng)) * traffic);
            factors = String.format(Locale.ROOT, "geo=exact,traffic=%.2f,status=%s",
                    traffic, assignment.get().getStatus());
        } else {
            // Monolith fallback branch (coordinates/destination unavailable):
            // status-based heuristic scaled by the same peak-hour band.
            boolean toCustomer = DeliveryAssignment.STATUS_PICKED_UP
                    .equalsIgnoreCase(assignment.get().getStatus());
            int base = toCustomer ? etaProperties.getFallbackMinutesToCustomer()
                    : etaProperties.getFallbackMinutesToPickup();
            minutes = (int) Math.ceil(base * traffic);
            factors = String.format(Locale.ROOT, "geo=heuristic,traffic=%.2f,status=%s",
                    traffic, assignment.get().getStatus());
        }

        int band = etaProperties.getConfidenceBandMinutes();
        LocalDateTime now = LocalDateTime.now();
        return Optional.of(new EtaSnapshot(minutes, now.plusMinutes(minutes),
                Math.max(0, minutes - band), minutes + band,
                traffic, 1.0, factors));
    }

    private RiderLocationUpdate freshRiderPosition(Long agentId) {
        if (agentId == null) {
            return null;
        }
        RiderLocationUpdate last =
                locationRepository.findTopByAgentIdOrderByRecordedAtDesc(agentId);
        if (last == null || last.getRecordedAt()
                .isBefore(LocalDateTime.now().minusMinutes(etaProperties.getLocationFreshnessMinutes()))) {
            return null;
        }
        return last;
    }

    /** Peak-hour traffic heuristic ported verbatim from the monolith OrderEtaService. */
    private static double resolveTrafficFactor() {
        int hour = LocalDateTime.now().getHour();
        if (hour >= 12 && hour < 14) return 1.15;
        if (hour >= 19 && hour < 22) return 1.25;
        if (hour >= 7 && hour < 10) return 1.1;
        return 1.0;
    }
}
