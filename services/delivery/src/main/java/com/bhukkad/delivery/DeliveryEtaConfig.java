package com.bhukkad.delivery;

import com.bhukkad.delivery.service.DistanceCalculator;
import com.bhukkad.delivery.service.EtaService;
import com.bhukkad.delivery.service.RoadNetworkDistanceCalculator;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * P3 / ADR-003 live-ETA wiring. Everything here is gated by
 * {@code app.delivery.eta.enabled} (default false ⇒ no beans, port keeps
 * returning "no ETA" exactly as before).
 *
 * <p>When enabled, {@code EtaService} (previously only unit-tested, never a
 * Spring bean) is registered with a {@link RoadNetworkDistanceCalculator} —
 * making it the single sanctioned production caller of
 * {@link RoadDistanceService}/{@link OsrmClient}. RoadDistanceService is
 * itself second-gated by {@code app.road-distance.enabled} (default false):
 * until OSRM is actually deployed the chain degrades to haversine, per the
 * ADR ("ETA uses haversine + zone factor until OSRM is actually deployed").</p>
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(DeliveryEtaProperties.class)
public class DeliveryEtaConfig {

    @Bean
    @ConditionalOnProperty(name = "app.delivery.eta.enabled", havingValue = "true")
    public DistanceCalculator roadNetworkDistanceCalculator(RoadDistanceService roadDistanceService) {
        return new RoadNetworkDistanceCalculator(roadDistanceService);
    }

    @Bean
    @ConditionalOnProperty(name = "app.delivery.eta.enabled", havingValue = "true")
    public EtaService etaService(DistanceCalculator roadNetworkDistanceCalculator) {
        return new EtaService(roadNetworkDistanceCalculator);
    }
}
