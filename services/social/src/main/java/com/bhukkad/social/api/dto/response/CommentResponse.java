package com.bhukkad.social.api.dto.response;

import java.time.LocalDateTime;

/**
 * Comment summary response.
 */
public record CommentResponse(
        Long id,
        Long postId,
        Long userId,
        Long parentCommentId,
        String content,
        LocalDateTime createdAt
) {
}
