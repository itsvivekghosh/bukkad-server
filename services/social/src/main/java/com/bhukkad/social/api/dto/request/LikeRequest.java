package com.bhukkad.social.api.dto.request;

import jakarta.validation.constraints.NotNull;

/**
 * Like/unlike request.
 */
public record LikeRequest(
        @NotNull(message = "postId is required")
        Long postId
) {
}
