package com.bhukkad.delivery;

import com.bhukkad.delivery.service.EtaDestinationResolver;
import com.bhukkad.delivery.service.EtaService;
import com.bhukkad.delivery.service.RoadNetworkDistanceCalculator;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class DeliveryEtaConfigTest {

    private final DeliveryEtaConfig config = new DeliveryEtaConfig();

    @Test
    void roadNetworkCalculator_wrapsRoadDistanceService() {
        assertThat(config.roadNetworkDistanceCalculator(mock(RoadDistanceService.class)))
                .isInstanceOf(RoadNetworkDistanceCalculator.class);
    }

    @Test
    void etaService_wrapsConfiguredCalculator() {
        assertThat(config.etaService((fromLat, fromLng, toLat, toLng) -> 4.0))
                .isInstanceOf(EtaService.class);
    }

    @Test
    void etaDestinationCoordinateRecord_isValueTyped() {
        EtaDestinationResolver.Coordinates a =
                new EtaDestinationResolver.Coordinates(12.97, 77.59);
        EtaDestinationResolver.Coordinates b =
                new EtaDestinationResolver.Coordinates(a.latitude(), a.longitude());

        assertThat(a).isEqualTo(b).hasSameHashCodeAs(b);
        assertThat(a).toString().contains("12.97");
        assertThat(Optional.of(b).map(EtaDestinationResolver.Coordinates::latitude)
                .orElseThrow()).isEqualTo(12.97);
    }
}
