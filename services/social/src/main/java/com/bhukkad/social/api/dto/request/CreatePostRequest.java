package com.bhukkad.social.api.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * Create post request.
 */
public record CreatePostRequest(
        @NotNull(message = "restaurantId is required")
        Long restaurantId,

        @NotBlank(message = "content is required")
        @Size(max = 2000, message = "content must be at most 2000 characters")
        String content,

        String[] mediaUrls,

        String postType,

        Double latitude,

        Double longitude
) {
}
