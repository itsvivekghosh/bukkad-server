package com.bhukkad.social.event;

/**
 * Event published when a like is toggled.
 *
 * @param postId the post ID
 * @param userId the user ID
 * @param liked  true if liked, false if unliked
 */
public record LikeToggledEvent(Long postId, Long userId, boolean liked) {
}
