package com.bhukkad.delivery.api;

import java.util.Optional;

/**
 * Live-ETA contract for the live-streaming module. Owns the {@code EtaSnapshot}
 * vocabulary so the live domain never touches the Order entity or the delivery
 * domain's internals (modular-monolith seam for the Phase 2 delivery-service
 * extraction — post-extraction this becomes an HTTP/gRPC call).
 */
public interface EtaPort {

    /**
     * @return the live ETA snapshot for the order, or empty when the order does
     *         not exist or no ETA can be computed yet
     */
    Optional<EtaSnapshot> computeEta(Long orderId);

    /**
     * Live ETA value object. Mirrors {@code OrderEtaService.EtaSnapshot} so the
     * wire shape is owned by the api package.
     */
    record EtaSnapshot(
            int minutes,
            java.time.LocalDateTime etaAt,
            int minMinutes,
            int maxMinutes,
            double trafficFactor,
            double surgeMultiplier,
            String factors
    ) {
    }
}
