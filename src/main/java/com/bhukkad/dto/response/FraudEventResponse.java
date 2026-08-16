package com.bhukkad.dto.response;

import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class FraudEventResponse {
    private Long id;
    private String eventType;
    private String ipAddress;
    private String deviceFingerprint;
    private String details;
    private String createdAt;
}