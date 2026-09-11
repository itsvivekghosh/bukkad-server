package com.bhukkad.delivery.api;

import com.bhukkad.delivery.DeliveryEtaConfig;
import com.bhukkad.delivery.DeliveryEtaProperties;
import com.bhukkad.delivery.RoadDistanceProperties;
import com.bhukkad.delivery.RoadDistanceService;
import com.bhukkad.delivery.domain.DeliveryAssignment;
import com.bhukkad.delivery.domain.DeliveryAssignmentRepository;
import com.bhukkad.delivery.domain.RiderLocationUpdate;
import com.bhukkad.delivery.domain.RiderLocationUpdateRepository;
import com.bhukkad.delivery.service.EtaService;
import com.bhukkad.delivery.service.RoadNetworkDistanceCalculator;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;

import java.time.LocalDateTime;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The null-object ETA contract is preserved as the GATE-OFF behavior
 * (P3 / ADR-003): without the gated EtaService bean every call is still
 * empty. Gate-on adds real snapshots — heuristics without coordinates,
 * geo-exact (EtaService via RoadNetworkDistanceCalculator, its first
 * sanctioned production caller) with a fresh rider position + a drop-off.
 */
class DefaultEtaPortTest {

    private final DeliveryAssignmentRepository assignments = mock(DeliveryAssignmentRepository.class);
    private final RiderLocationUpdateRepository locations = mock(RiderLocationUpdateRepository.class);

    /** The gated bean chain with OSRM left disabled (RoadDistanceService haversine). */
    private final EtaService eta = new EtaService(new RoadNetworkDistanceCalculator(
            new RoadDistanceService(new RoadDistanceProperties(), null)));

    private DefaultEtaPort gateOff() {
        return new DefaultEtaPort(provided(null), etaProperties(), assignments, locations);
    }

    private DefaultEtaPort gateOn() {
        return new DefaultEtaPort(provided(eta), etaProperties(), assignments, locations);
    }

    private DeliveryEtaProperties etaProperties() {
        return new DeliveryEtaProperties();
    }

    @SuppressWarnings("unchecked")
    private ObjectProvider<EtaService> provided(EtaService value) {
        ObjectProvider<EtaService> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(value);
        return provider;
    }

    private void assignment(String status) {
        DeliveryAssignment a = new DeliveryAssignment();
        a.setOrderId(1L);
        a.setAgentId(9L);
        a.setStatus(status);
        a.setAssignedAt(LocalDateTime.now());
        when(assignments.findByOrderId(1L)).thenReturn(Optional.of(a));
    }

    private void riderPosition(double lat, double lng, LocalDateTime recordedAt) {
        RiderLocationUpdate u = new RiderLocationUpdate();
        u.setAgentId(9L);
        u.setLatitude(lat);
        u.setLongitude(lng);
        u.setRecordedAt(recordedAt);
        when(locations.findTopByAgentIdOrderByRecordedAtDesc(9L)).thenReturn(u);
    }

    @Test
    void computeEta_isAlwaysEmpty_whenGateOff() {
        assertThat(gateOff().computeEta(42L)).isEmpty();
    }

    @Test
    void computeEta_emptyForUnknownOrder_evenGateOn() {
        // findByOrderId unstubbed → Optional.empty() from Mockito defaults.
        assertThat(gateOn().computeEta(99L)).isEmpty();
    }

    @Test
    void computeEta_emptyForDelivered_evenGateOn() {
        assignment(DeliveryAssignment.STATUS_DELIVERED);
        assertThat(gateOn().computeEta(1L)).isEmpty();
    }

    @Test
    void computeEta_gateOn_heuristicWithoutDropoffCoords() {
        assignment(DeliveryAssignment.STATUS_ASSIGNED);
        riderPosition(19.0, 72.0, LocalDateTime.now());

        Optional<EtaPort.EtaSnapshot> snapshot = gateOn().computeEta(1L);

        assertThat(snapshot).isPresent();
        assertThat(snapshot.get().minutes()).isGreaterThanOrEqualTo(etaProperties().getFallbackMinutesToPickup());
        assertThat(snapshot.get().factors()).contains("geo=heuristic");
        assertThat(snapshot.get().minMinutes()).isLessThanOrEqualTo(snapshot.get().minutes());
        assertThat(snapshot.get().maxMinutes()).isGreaterThanOrEqualTo(snapshot.get().minutes());
    }

    @Test
    void computeEta_gateOn_stalePositionAlsoDegradesToHeuristic() {
        assignment(DeliveryAssignment.STATUS_ASSIGNED);
        riderPosition(19.0, 72.0, LocalDateTime.now().minusHours(3)); // > 10 min window

        assertThat(gateOn().computeEta(1L, 19.1, 72.9))
                .get().extracting(EtaPort.EtaSnapshot::factors).asString().contains("geo=heuristic");
    }

    @Test
    void computeEta_gateOn_geoExactWithFreshPositionAndDropoff() {
        assignment(DeliveryAssignment.STATUS_ASSIGNED);
        riderPosition(12.97, 77.60, LocalDateTime.now());

        Optional<EtaPort.EtaSnapshot> snapshot = gateOn().computeEta(1L, 12.93, 77.62);

        assertThat(snapshot).isPresent();
        assertThat(snapshot.get().factors()).contains("geo=exact");
        // Rider is ~4 km away; ETA = ceil((4km/20kmh→12min + 15min prep) * [0..1.25]) ≥ 27 outside peaks.
        assertThat(snapshot.get().minutes()).isGreaterThan(0);
    }

    @Test
    void pickedUpOrdersUseTheCustomerLegHeuristic() {
        assignment(DeliveryAssignment.STATUS_PICKED_UP);
        riderPosition(19.0, 72.0, LocalDateTime.now());

        Optional<EtaPort.EtaSnapshot> snapshot = gateOn().computeEta(1L);

        assertThat(snapshot).isPresent();
        assertThat(snapshot.get().factors()).contains("status=PICKED_UP");
    }

    @Test
    void roadNetworkCalculator_distanceMatchesHaversineWhenOsrmDisabled() {
        // RoadDistanceProperties default enabled=false → its haversine fallback
        // is numerically identical to DistanceCalculator.calculateDistance.
        RoadNetworkDistanceCalculator calc = new RoadNetworkDistanceCalculator(
                new RoadDistanceService(new RoadDistanceProperties(), null));
        double viaChain = calc.distanceKm(19.0760, 72.8777, 18.5204, 73.8567);
        double viaHaversine = com.bhukkad.delivery.util.DistanceCalculator
                .calculateDistance(19.0760, 72.8777, 18.5204, 73.8567);
        assertThat(viaChain).isCloseTo(viaHaversine, org.assertj.core.data.Offset.offset(0.0001));
        assertThat(viaChain).isBetween(100.0, 180.0);
    }

    @Test
    void etaServiceBeansArePropertyGated() {
        // Documented gate: no EtaService bean unless app.delivery.eta.enabled=true.
        for (String method : new String[]{"roadNetworkDistanceCalculator", "etaService"}) {
            var m = java.util.Arrays.stream(DeliveryEtaConfig.class.getDeclaredMethods())
                    .filter(x -> x.getName().equals(method))
                    .findFirst().orElseThrow();
            ConditionalOnProperty gate = m.getAnnotation(ConditionalOnProperty.class);
            assertThat(gate).as(method + " gate").isNotNull();
            assertThat(gate.name()).containsExactly("app.delivery.eta.enabled");
            assertThat(gate.havingValue()).isEqualTo("true");
        }
    }
}
