package com.bhukkad.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * Rider-submitted payload confirming proof of delivery at the doorstep.
 *
 * <p>Sent to {@code POST /api/v1/orders/delivery/{orderId}/proof/verify}. Every field is optional
 * except {@code otpCode}, because the two proof modes carry different evidence: an OTP handover
 * always produces a code, while the photo, recipient name, and GPS fix are supporting context the
 * rider app attaches when it has them.
 *
 * <p>The OTP itself is never returned to the rider by any endpoint — it is sent by SMS to the
 * customer, who reads it out at handover. This request is the only place the plaintext code enters
 * the server, and it is compared against a BCrypt hash and then discarded.
 */
@Data
public class DeliveryProofVerifyRequest {

    /**
     * The code the customer read out to the rider.
     *
     * <p>Constrained to exactly 6 digits to match {@code Constants.OTP_LENGTH}. Rejecting
     * malformed input here means a typo does not consume one of the rider's limited verification
     * attempts.
     */
    @NotBlank(message = "OTP code is required")
    @Pattern(regexp = "\\d{6}", message = "OTP code must be 6 digits")
    private String otpCode;

    /**
     * Object storage key of an already-uploaded doorstep photo, if the rider took one.
     *
     * <p>Obtained from {@code POST /api/v1/orders/delivery/{orderId}/proof/photo-url}. The rider
     * app uploads directly to storage with the presigned URL, then reports the key here. Validated
     * server-side against the delivery-proof key prefix, since this value comes from the client.
     */
    @Size(max = 512, message = "Photo key must not exceed 512 characters")
    private String photoKey;

    /** Name of whoever actually took the order, when it was not the customer themselves. */
    @Size(max = 120, message = "Recipient name must not exceed 120 characters")
    private String recipientName;

    /** Rider's latitude at handover, used to spot proofs submitted far from the address. */
    private Double captureLatitude;

    /** Rider's longitude at handover, used to spot proofs submitted far from the address. */
    private Double captureLongitude;

    /** Free-text rider note, e.g. "left with security guard at gate". */
    @Size(max = 1000, message = "Notes must not exceed 1000 characters")
    private String notes;
}
