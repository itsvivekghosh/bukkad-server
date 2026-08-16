package com.bhukkad.dto.response;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Response for a delivery-proof photo upload request.
 *
 * <h2>Contract with the rider app</h2>
 * <ol>
 *   <li>Rider app calls {@code POST /orders/delivery/{orderId}/proof/photo-url} with a
 *       content type and receives this payload.</li>
 *   <li>It PUTs the photo bytes directly to {@link #uploadUrl} using that exact content
 *       type. The signature covers the content type, so sending a different one fails.</li>
 *   <li>It submits {@link #photoKey} as {@code photoKey} on the verify call, which is
 *       what persists the photo against the proof row.</li>
 * </ol>
 *
 * <p>The key is generated server-side and namespaced under this feature's prefix, so a
 * rider cannot point a proof at an invoice or menu-image object. The verify endpoint
 * re-validates the prefix before storing it.
 *
 * <p>Nothing is recorded on the proof row at URL-issue time: an unused URL simply
 * expires. The photo only becomes part of the proof when its key comes back on verify.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DeliveryProofPhotoUploadResponse {

    /** Short-lived presigned HTTPS URL the rider app PUTs the photo bytes to. */
    private String uploadUrl;

    /** Server-generated object key to send back on the verify call. */
    private String photoKey;
}
