package com.bhukkad.dto.response;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class OrderTimelineEventResponse {
    private Long id;
    private Long orderId;
    private String eventType;
    private String status;
    private String message;
    private Long actorId;
    private String actorRole;
    private String createdAt;
}
