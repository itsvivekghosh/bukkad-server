package com.bhukkad.dto.response;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Proof-of-delivery record for a single order.
 *
 * <p>Returned to the rider when an OTP is issued or verified, and to support/ops staff when
 * investigating a delivery dispute. Timestamps are ISO-8601 strings, matching
 * {@link OrderInvoiceResponse}, so clients get a stable wire format regardless of server locale.
 *
 * <p><strong>This DTO deliberately has no OTP code field.</strong> Only a BCrypt hash of the code
 * is ever persisted, and the plaintext exists solely in the SMS sent to the customer. Exposing it
 * here — even to the rider who is supposed to collect it — would defeat the entire purpose of the
 * handover check, since a rider could then mark an order delivered without ever meeting the
 * customer. {@link #otpExpiresAt} and {@link #otpAttemptsRemaining} give the rider app everything
 * it needs to render a useful OTP screen without revealing the secret.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DeliveryProofResponse {

    private Long id;
    private Long orderId;
    private String orderNumber;

    /** Which evidence this proof carries: {@code OTP}, {@code PHOTO}, {@code OTP_AND_PHOTO}, or {@code SKIPPED}. */
    private String proofType;

    /** Lifecycle state: {@code PENDING}, {@code VERIFIED}, {@code FAILED}, or {@code SKIPPED}. */
    private String status;

    /** When the current OTP was sent to the customer, or {@code null} if none has been issued. */
    private String otpIssuedAt;

    /** When the current OTP stops being accepted. Drives the countdown in the rider app. */
    private String otpExpiresAt;

    /**
     * Verification attempts left before the proof is locked as {@code FAILED}.
     *
     * <p>Surfaced so the rider app can warn before the last try and prompt a resend instead of
     * burning the final attempt on a misheard digit.
     */
    private Integer otpAttemptsRemaining;

    /** When the code was accepted, or {@code null} while the proof is still pending. */
    private String verifiedAt;

    /** True when a doorstep photo has been attached to this proof. */
    private Boolean photoAvailable;

    /** Short-lived presigned URL for the doorstep photo, or {@code null} when there is none. */
    private String photoUrl;

    /** Who actually received the order, when it was not the customer. */
    private String recipientName;

    /** Rider's free-text note captured at handover. */
    private String notes;

    /**
     * Whether this proof currently satisfies the delivery gate.
     *
     * <p>True for both {@code VERIFIED} and {@code SKIPPED}: a proof waived by ops is as final as
     * one the customer confirmed. Clients should read this rather than comparing status strings
     * themselves.
     */
    private Boolean satisfied;

    /**
     * Whether an unsatisfied proof actually blocks completing the delivery.
     *
     * <p>Reflects {@code app.delivery.proof.enforced}. While false, the rider app can show the OTP
     * flow and let riders build the habit without stranding deliveries if the code never arrives.
     */
    private Boolean enforced;

    private String createdAt;
}
