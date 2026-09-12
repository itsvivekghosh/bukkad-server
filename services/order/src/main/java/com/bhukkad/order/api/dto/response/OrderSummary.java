package com.bhukkad.order.api.dto.response;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import com.bhukkad.order.domain.entity.Order;

/**
 * Read-model projection of an order exposed to other domains (payment,
 * delivery, live, notification). This is the ONLY type other domains may
 * consume — never the {@code com.bhukkad.entity.Order} aggregate.
 *
 * <p>When the order service is physically extracted, this record becomes the
 * response of {@code GET /orders/{id}/summary} (or the gRPC equivalent) and
 * callers switch to an HTTP/gRPC client with zero local logic changes.</p>
 *
 * <p>Immutable on purpose: cross-domain consumers get a stable snapshot, not a
 * live handle into the order domain's persistence context.</p>
 */
public record OrderSummary(
        Long id,
        String orderNumber,
        Long customerId,
        Long restaurantId,
        Long deliveryAgentId,
        String status,
        BigDecimal totalAmount,
        BigDecimal subtotal,
        BigDecimal tipAmount,
        Integer loyaltyPointsRedeemed,
        AddressBrief deliveryAddress,
        LocalDateTime createdAt,
        LocalDateTime scheduledAt,
        Integer liveEtaMinutes,
        LocalDateTime liveEtaAt
) {

    /** Minimal delivery-address projection for routing and ETA work. */
    public record AddressBrief(
            Long id,
            String addressLine1,
            String city,
            String pincode,
            Double latitude,
            Double longitude
    ) {
    }
}
