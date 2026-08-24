package com.bhukkad.dto.response;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class OrderLiveUpdate {

    public enum EventType {
        ORDER_CREATED,
        STATUS_CHANGED,
        AGENT_ASSIGNED,
        RIDER_LOCATION
    }

    private EventType eventType;
    private Long eventId;
    private Long orderId;
    private String orderNumber;
    private Long customerId;
    private Long restaurantId;
    private Long deliveryAgentId;
    private String previousStatus;
    private String status;
    private LocalDateTime changedAt;
    private Integer liveEtaMinutes;
    private LocalDateTime liveEtaAt;
    /** Rider GPS latitude; only set for {@link EventType#RIDER_LOCATION}. */
    private Double latitude;
    /** Rider GPS longitude; only set for {@link EventType#RIDER_LOCATION}. */
    private Double longitude;
}
