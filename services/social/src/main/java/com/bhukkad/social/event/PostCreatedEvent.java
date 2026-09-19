package com.bhukkad.social.event;

/**
 * Event published when a new post is created.
 *
 * @param postId      the post ID
 * @param latitude    post latitude
 * @param longitude   post longitude
 * @param restaurantId the restaurant ID
 */
public record PostCreatedEvent(Long postId, double latitude, double longitude, Long restaurantId) {
}
