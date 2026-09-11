package com.bhukkad.delivery.api;

import com.bhukkad.delivery.RoadDistanceService;
import com.bhukkad.delivery.domain.DeliveryAssignment;
import com.bhukkad.delivery.domain.DeliveryAssignmentRepository;
import com.bhukkad.delivery.domain.RiderLocationUpdate;
import com.bhukkad.delivery.domain.RiderLocationUpdateRepository;
import com.bhukkad.delivery.service.EtaDestinationResolver;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.beans.factory.ObjectProvider;

import java.time.LocalDateTime;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

/**
 * Live-ETA port: empty unless {@code app.eta.enabled} turned the EtaService
 * bean on AND the order is assigned AND the rider has a last-known position
 * AND a destination resolver supplies the order's coordinates. The default
 * (flag off / no resolver) stays the historical always-empty answer.
 */
@ExtendWith(MockitoExtension.class)
class DefaultEtaPortTest {

    @Mock private ObjectProvider<com.bhukkad.delivery.service.EtaService> etaServiceProvider;
    @Mock private DeliveryAssignmentRepository assignmentRepository;
    @Mock private RiderLocationUpdateRepository locationRepository;
    @Mock private RoadDistanceService roadDistanceService;
    @Mock private ObjectProvider<EtaDestinationResolver> destinationProvider;
    @Mock private EtaDestinationResolver destinationResolver;

    private DefaultEtaPort port() {
        return new DefaultEtaPort(etaServiceProvider, assignmentRepository,
                locationRepository, roadDistanceService, destinationProvider);
    }

    private void flagOn() {
        lenient().when(etaServiceProvider.getIfAvailable())
                .thenReturn(new com.bhukkad.delivery.service.EtaService(
                        (fLat, fLng, tLat, tLng) -> 5.0));
    }

    private void assignedRiderWithPosition(long orderId, long agentId) {
        DeliveryAssignment assignment = new DeliveryAssignment();
        assignment.setOrderId(orderId);
        assignment.setAgentId(agentId);
        assignment.setStatus(DeliveryAssignment.STATUS_ASSIGNED);
        lenient().when(assignmentRepository.findByOrderId(orderId))
                .thenReturn(Optional.of(assignment));
        RiderLocationUpdate position = new RiderLocationUpdate();
        position.setAgentId(agentId);
        position.setLatitude(19.076);
        position.setLongitude(72.877);
        position.setRecordedAt(LocalDateTime.now());
        lenient().when(locationRepository.findFirstByAgentIdOrderByRecordedAtDesc(agentId))
                .thenReturn(Optional.of(position));
    }

    @Test
    void computeEta_isEmptyWhenFlagOff() {
        // No EtaService bean (app.eta.enabled=false, the default).
        when(etaServiceProvider.getIfAvailable()).thenReturn(null);

        assertThat(port().computeEta(42L)).isEmpty();
    }

    @Test
    void computeEta_emptyForUnknownOrder() {
        flagOn();
        when(assignmentRepository.findByOrderId(42L)).thenReturn(Optional.empty());

        assertThat(port().computeEta(42L)).isEmpty();
        assertThat(port().computeEta(null)).isEmpty();
    }

    @Test
    void computeEta_emptyWhenRiderHasNoPosition() {
        flagOn();
        DeliveryAssignment assignment = new DeliveryAssignment();
        assignment.setOrderId(42L);
        assignment.setAgentId(9L);
        when(assignmentRepository.findByOrderId(42L)).thenReturn(Optional.of(assignment));
        when(locationRepository.findFirstByAgentIdOrderByRecordedAtDesc(9L))
                .thenReturn(Optional.empty());

        assertThat(port().computeEta(42L)).isEmpty();
    }

    @Test
    void computeEta_emptyWhenNoDestinationResolver() {
        flagOn();
        assignedRiderWithPosition(42L, 9L);
        when(destinationProvider.orderedStream()).thenReturn(java.util.stream.Stream.empty());

        assertThat(port().computeEta(42L)).isEmpty();
    }

    @Test
    void computeEta_computesSnapshotWhenDestinationKnown() {
        flagOn();
        assignedRiderWithPosition(42L, 9L);
        when(destinationProvider.orderedStream()).thenReturn(java.util.stream.Stream.of(destinationResolver));
        when(destinationResolver.destinationOf(42L))
                .thenReturn(Optional.of(new EtaDestinationResolver.Coordinates(19.12, 72.85)));
        // 12 road minutes from OSRM (or fallback).
        when(roadDistanceService.route(anyDouble(), anyDouble(), anyDouble(), anyDouble()))
                .thenReturn(new RoadDistanceService.RoadRoute(4.0, 12.0, true));

        Optional<EtaPort.EtaSnapshot> eta = port().computeEta(42L);

        assertThat(eta).isPresent();
        EtaPort.EtaSnapshot snapshot = eta.get();
        // 12 travel (floored at 5) + 15 prep = 27, scaled by the hour-of-day
        // traffic factor (the suite can run inside a rush-hour window).
        double factor = DefaultEtaPort.trafficFactor(LocalDateTime.now().getHour());
        int expected = (int) Math.ceil(27 * factor);
        assertThat(snapshot.minutes()).isEqualTo(expected);
        assertThat(snapshot.minMinutes()).isEqualTo(expected - CONFIDENCE_BAND);
        assertThat(snapshot.maxMinutes()).isEqualTo(expected + CONFIDENCE_BAND);
        assertThat(snapshot.etaAt()).isAfter(LocalDateTime.now());
        assertThat(snapshot.factors()).contains("osrm=true");
        assertThat(snapshot.surgeMultiplier()).isEqualTo(1.0);
    }

    private static final int CONFIDENCE_BAND = 5;

    @Test
    void computeEta_emptyWhenResolverKnowsNothingAboutTheOrder() {
        flagOn();
        assignedRiderWithPosition(42L, 9L);
        when(destinationProvider.orderedStream()).thenReturn(java.util.stream.Stream.of(destinationResolver));
        when(destinationResolver.destinationOf(42L)).thenReturn(Optional.empty());

        assertThat(port().computeEta(42L)).isEmpty();
    }

    @Test
    void trafficFactor_peaksAtRushHours() {
        assertThat(DefaultEtaPort.trafficFactor(13)).isEqualTo(1.15);
        assertThat(DefaultEtaPort.trafficFactor(20)).isEqualTo(1.25);
        assertThat(DefaultEtaPort.trafficFactor(8)).isEqualTo(1.1);
        assertThat(DefaultEtaPort.trafficFactor(3)).isEqualTo(1.0);
    }
}
