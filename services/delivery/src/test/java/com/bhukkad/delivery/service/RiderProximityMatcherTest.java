package com.bhukkad.delivery.service;

import com.bhukkad.delivery.GeoMatchingProperties;
import com.bhukkad.delivery.domain.AgentActiveLoad;
import com.bhukkad.delivery.domain.DeliveryAgent;
import com.bhukkad.delivery.domain.DeliveryAgentRepository;
import com.bhukkad.delivery.domain.DeliveryAssignmentRepository;
import com.bhukkad.delivery.domain.RiderLocationUpdate;
import com.bhukkad.delivery.domain.RiderLocationUpdateRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * ADR-003 nearest-rider matching: gate, freshness window, capacity, and the
 * nearest-wins ranking over a bounded candidate set.
 */
@ExtendWith(MockitoExtension.class)
class RiderProximityMatcherTest {

    @Mock private RiderLocationUpdateRepository locationRepository;
    @Mock private DeliveryAssignmentRepository assignmentRepository;
    @Mock private DeliveryAgentRepository agentRepository;

    private GeoMatchingProperties properties;
    private RiderProximityMatcher matcher;

    /** Anchor: Mumbai CST. */
    private static final double ANCHOR_LAT = 19.0760;
    private static final double ANCHOR_LNG = 72.8777;

    @BeforeEach
    void setUp() {
        properties = new GeoMatchingProperties();
        properties.setEnabled(true);
        matcher = new RiderProximityMatcher(properties, locationRepository,
                assignmentRepository, agentRepository);
    }

    private RiderLocationUpdate pos(Long agentId, double lat, double lng) {
        RiderLocationUpdate u = new RiderLocationUpdate();
        u.setAgentId(agentId);
        u.setLatitude(lat);
        u.setLongitude(lng);
        u.setRecordedAt(LocalDateTime.now());
        return u;
    }

    private void activeAgent(Long id) {
        DeliveryAgent a = new DeliveryAgent();
        a.setId(id);
        a.setIsActive(true);
        when(agentRepository.findById(id)).thenReturn(Optional.of(a));
    }

    @Test
    void flagDisabled_neverConsultsRepositories() {
        properties.setEnabled(false);
        assertThat(matcher.nearestEligible(ANCHOR_LAT, ANCHOR_LNG)).isEmpty();
    }

    @Test
    void noAnchor_defersToCallerFallback() {
        assertThat(matcher.nearestEligible(null, ANCHOR_LNG)).isEmpty();
    }

    @Test
    void nearestWins_amongThreeFreshCandidates() {
        // near (0.01°), mid (0.1°), far (1.0°) — NEAREST must take the order.
        when(locationRepository.findActiveAgentsLastPositions(any(), anyInt()))
                .thenReturn(List.of(
                        pos(2L, ANCHOR_LAT + 0.1, ANCHOR_LNG + 0.1),
                        pos(1L, ANCHOR_LAT + 0.01, ANCHOR_LNG + 0.01),
                        pos(3L, ANCHOR_LAT + 1.0, ANCHOR_LNG + 1.0)));
        when(assignmentRepository.countActiveLoadByAgentIds(any())).thenReturn(List.of());
        activeAgent(1L);

        assertThat(matcher.nearestEligible(ANCHOR_LAT, ANCHOR_LNG))
                .map(DeliveryAgent::getId).contains(1L);
    }

    @Test
    void capacityExcluded_riderAtCapLosesToNextNearest() {
        when(locationRepository.findActiveAgentsLastPositions(any(), anyInt()))
                .thenReturn(List.of(
                        pos(1L, ANCHOR_LAT + 0.01, ANCHOR_LNG + 0.01),
                        pos(2L, ANCHOR_LAT + 0.2, ANCHOR_LNG + 0.2)));
        when(assignmentRepository.countActiveLoadByAgentIds(any()))
                .thenReturn(List.of(new AgentActiveLoad(1L, 3L))); // rider 1 at cap (3)
        activeAgent(2L);

        assertThat(matcher.nearestEligible(ANCHOR_LAT, ANCHOR_LNG))
                .map(DeliveryAgent::getId).contains(2L);
    }

    @Test
    void everyRiderAtCap_returnsEmptyForLegacyFallback() {
        when(locationRepository.findActiveAgentsLastPositions(any(), anyInt()))
                .thenReturn(List.of(pos(1L, ANCHOR_LAT + 0.01, ANCHOR_LNG + 0.01)));
        when(assignmentRepository.countActiveLoadByAgentIds(any()))
                .thenReturn(List.of(new AgentActiveLoad(1L, 5L)));

        assertThat(matcher.nearestEligible(ANCHOR_LAT, ANCHOR_LNG)).isEmpty();
    }

    @Test
    void freshnessWindow_queryUsesConfiguredWindow_andBoundsCandidates() {
        when(locationRepository
                .findActiveAgentsLastPositions(any(LocalDateTime.class), anyInt())).thenReturn(List.of());

        assertThat(matcher.nearestEligible(ANCHOR_LAT, ANCHOR_LNG)).isEmpty();

        ArgumentCaptor<LocalDateTime> since = ArgumentCaptor.forClass(LocalDateTime.class);
        ArgumentCaptor<Integer> limit = ArgumentCaptor.forClass(Integer.class);
        verify(locationRepository).findActiveAgentsLastPositions(since.capture(), limit.capture());
        // The SQL does the stale-row cutoff; the matcher must hand it a
        // now - freshness window and the bounded candidate limit.
        long skewSeconds = Math.abs(java.time.Duration
                .between(since.getValue(),
                        LocalDateTime.now().minusMinutes(properties.getLocationFreshnessMinutes()))
                .getSeconds());
        assertThat(skewSeconds).isLessThanOrEqualTo(5);
        assertThat(limit.getValue()).isEqualTo(properties.getCandidateLimit());
    }

    @Test
    void emptyCandidateSet_returnsEmptyForLegacyFallback() {
        when(locationRepository.findActiveAgentsLastPositions(any(), anyInt())).thenReturn(List.of());
        assertThat(matcher.nearestEligible(ANCHOR_LAT, ANCHOR_LNG)).isEmpty();
    }

    @Test
    void dedupesEqualMaxTimestampRows() {
        when(locationRepository.findActiveAgentsLastPositions(any(), anyInt()))
                .thenReturn(List.of(pos(1L, ANCHOR_LAT + 0.01, ANCHOR_LNG + 0.01),
                        pos(1L, ANCHOR_LAT + 0.6, ANCHOR_LNG + 0.6)));
        when(assignmentRepository.countActiveLoadByAgentIds(any())).thenReturn(List.of());
        activeAgent(1L);

        assertThat(matcher.nearestEligible(ANCHOR_LAT, ANCHOR_LNG))
                .map(DeliveryAgent::getId).contains(1L);
        @SuppressWarnings("unchecked")
        ArgumentCaptor<java.util.Collection<Long>> ids = ArgumentCaptor.forClass(java.util.Collection.class);
        verify(assignmentRepository).countActiveLoadByAgentIds(ids.capture());
        assertThat(ids.getValue()).containsExactly(1L); // deduped to one agent
    }
}
