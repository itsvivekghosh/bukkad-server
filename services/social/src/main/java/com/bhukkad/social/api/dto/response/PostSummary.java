package com.bhukkad.social.api.dto.response;

/**
 * Public post summary returned in feed listings.
 */
public record PostSummary(
        Long id,
        Long restaurantId,
        String restaurantName,
        Long authorId,
        String authorName,
        String content,
        String[] mediaUrls,
        String postType,
        Integer likeCount,
        Integer commentCount,
        String status,
        java.time.LocalDateTime createdAt
) {
}
