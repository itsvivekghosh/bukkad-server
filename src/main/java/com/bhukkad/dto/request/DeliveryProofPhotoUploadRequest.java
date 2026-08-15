package com.bhukkad.dto.request;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

/**
 * Request body for minting a presigned upload URL for a delivery-proof photo.
 *
 * <h2>Why only a content type</h2>
 * The rider app never streams bytes through this server. It asks for a URL, PUTs the
 * photo straight to object storage, and then hands the returned key back on
 * {@code POST /delivery/{orderId}/proof/verify}. The only thing the server needs up
 * front is the content type, because that decides the object extension and is checked
 * against the allowlist in
 * {@code DeliveryProofPhotoStorageService#validateContentType(String)} before any URL
 * is signed. Rejecting here keeps non-image payloads out of the proof bucket.
 *
 * <p>The storage key itself is server-generated on purpose — a client-chosen key would
 * let a rider overwrite another order's proof.
 */
@Data
public class DeliveryProofPhotoUploadRequest {

    /**
     * MIME type of the photo about to be uploaded, e.g. {@code image/jpeg}.
     * Must be one of the types the proof storage service allows.
     */
    @NotBlank(message = "Content type is required")
    private String contentType;
}
