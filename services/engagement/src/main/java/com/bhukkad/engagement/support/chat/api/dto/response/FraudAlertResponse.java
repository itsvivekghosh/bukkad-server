package com.bhukkad.engagement.support.chat.api.dto.response;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Response DTO for fraud alerts (admin view).
 */
public record FraudAlertResponse(
    UUID publicId,
    Long roomId,
    Long messageId,
    Long customerId,
    String alertType,
    com.bhukkad.engagement.support.chat.domain.entity.FraudAlert.Severity severity,
    Integer riskScore,
    String description,
    String evidence,
    Boolean reviewed,
    String reviewedBy,
    LocalDateTime reviewedAt,
    String reviewNote,
    String actionTaken,
    LocalDateTime createdAt
) {}