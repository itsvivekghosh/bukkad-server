package com.bhukkad.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * Payload for a restaurant owner's public reply to a customer review.
 *
 * <p>Sent to {@code POST /api/v1/restaurants/owner/reviews/{reviewId}/response}. The reply is
 * stored in {@code reviews.owner_response} ({@code TEXT}); the 2000-character ceiling here is a
 * product limit, not a storage one, and keeps a reply readable next to the review it answers.
 */
@Data
public class ReviewResponseRequest {

    /** The owner's reply. Must be non-blank; send a delete request instead of an empty reply. */
    @NotBlank(message = "Response is required")
    @Size(max = 2000, message = "Response must not exceed 2000 characters")
    private String response;
}
