package com.bhukkad.social.api.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * Create comment request.
 */
public record CommentRequest(
        @NotNull(message = "postId is required")
        Long postId,

        @NotBlank(message = "content is required")
        @Size(max = 1000, message = "comment must be at most 1000 characters")
        String content,

        Long parentCommentId
) {
}
