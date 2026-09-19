package com.bhukkad.social.event;

/**
 * Event published when a post is deleted.
 *
 * @param postId      the post ID
 * @param latitude    post latitude
 * @param longitude   post longitude
 * @param restaurantId the restaurant ID
 */
public record PostDeletedEvent(Long postId, double latitude, double longitude, Long restaurantId) {
}
