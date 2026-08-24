package com.bhukkad.dto.response;

import java.time.LocalDateTime;

public record MenuVersionResponse(
        Long id,
        Long restaurantId,
        Integer versionNumber,
        String label,
        String status,
        LocalDateTime createdAt,
        LocalDateTime publishedAt
) {}