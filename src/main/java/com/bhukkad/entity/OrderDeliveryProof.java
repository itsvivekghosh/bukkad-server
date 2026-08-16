package com.bhukkad.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * Proof that an order was handed to the right person.
 *
 * <p>Maps the {@code order_delivery_proofs} table created in migration
 * {@code V17__trust_and_compliance.sql}. Exactly one row exists per order
 * (enforced by {@code uk_delivery_proof_order}), so this is modelled as a
 * {@link OneToOne} owning side rather than a history table: the OTP can be
 * reissued, but a delivery has only one final outcome.</p>
 *
 * <p><strong>The plaintext OTP is never persisted.</strong> Only
 * {@link #otpCodeHash} is stored, and it is a one-way hash, so a database dump
 * cannot be replayed to fake deliveries. The plaintext is returned exactly once
 * to the customer channel that must present it; the rider never receives it.</p>
 *
 * <p>Kept in its own table rather than as columns on {@code orders} for two
 * reasons: the hot {@code orders} row stays narrow for the high-traffic listing
 * and tracking queries, and the hash column can be purged independently once the
 * retention window for delivery evidence expires.</p>
 */
@Entity
@Table(name = "order_delivery_proofs")
@Data
@NoArgsConstructor
@AllArgsConstructor
public class OrderDeliveryProof {

    /**
     * How the handover is evidenced.
     *
     * <p>Persisted as a string so the numeric ordinal never leaks into the
     * schema and new variants can be appended safely.</p>
     */
    public enum ProofType {
        /** Customer reads a 6-digit code to the rider. The default. */
        OTP,
        /** Rider uploads a photo of the handover or drop-off point. */
        PHOTO,
        /** Both are required, used for high-value orders. */
        OTP_AND_PHOTO,
        /** Proof was waived by support; requires a reason in {@code notes}. */
        SKIPPED
    }

    /** Lifecycle of the proof, driving whether delivery may be completed. */
    public enum ProofStatus {
        /** Created, awaiting a successful verification. */
        PENDING,
        /** OTP matched (and/or photo captured); delivery may complete. */
        VERIFIED,
        /** Attempt cap exhausted; needs a reissue or a support override. */
        FAILED,
        /** Deliberately bypassed; delivery may complete. */
        SKIPPED
    }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /**
     * The order being delivered. LAZY because callers that already hold the
     * {@code Order} only need the proof's own columns, and
     * {@code spring.jpa.open-in-view=false} means an accidental traversal
     * outside a transaction fails loudly rather than issuing a hidden query.
     */
    @OneToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "order_id", nullable = false, unique = true)
    private Order order;

    /**
     * Rider who performed the verification. Nullable because the proof row is
     * created when the OTP is issued, which can happen before dispatch assigns
     * an agent.
     */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "agent_id")
    private DeliveryAgent agent;

    @Enumerated(EnumType.STRING)
    @Column(name = "proof_type", nullable = false, length = 20)
    private ProofType proofType = ProofType.OTP;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private ProofStatus status = ProofStatus.PENDING;

    /**
     * One-way hash of the current OTP. Nullable for photo-only proofs and
     * cleared once the code has been consumed so a verified row holds no
     * credential material at all.
     */
    @Column(name = "otp_code_hash", length = 128)
    private String otpCodeHash;

    /** When the current code was generated; used for reissue rate limiting. */
    private LocalDateTime otpIssuedAt;

    /** Hard expiry of the current code. A code past this is never accepted. */
    private LocalDateTime otpExpiresAt;

    /**
     * Failed verification attempts against the current code. Reset on reissue
     * and capped by the service so the 6-digit space cannot be brute forced.
     */
    @Column(name = "otp_attempts", nullable = false)
    private Integer otpAttempts = 0;

    /** When verification succeeded; {@code null} while unverified. */
    private LocalDateTime verifiedAt;

    /** Object-storage key of the handover photo, or {@code null} if none. */
    @Column(length = 512)
    private String photoStorageKey;

    private LocalDateTime photoUploadedAt;

    /**
     * Who actually took the order, captured when it is not the customer (a
     * guard, a neighbour). Free text because it is evidence, not an identifier.
     */
    @Column(length = 120)
    private String recipientName;

    /**
     * Rider coordinates at the moment of verification. Stored to let ops detect
     * codes read out over the phone from far away from the drop point.
     */
    private Double captureLatitude;

    private Double captureLongitude;

    /** Rider or support notes, and the mandatory reason when skipping. */
    @Column(columnDefinition = "TEXT")
    private String notes;

    @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(nullable = false)
    private LocalDateTime updatedAt;

    @PrePersist
    protected void onCreate() {
        LocalDateTime now = LocalDateTime.now();
        this.createdAt = now;
        this.updatedAt = now;
    }

    @PreUpdate
    protected void onUpdate() {
        this.updatedAt = LocalDateTime.now();
    }

    /**
     * Whether delivery completion is allowed for this proof.
     *
     * @return {@code true} when the handover was verified or deliberately waived
     */
    @Transient
    public boolean isSatisfied() {
        return status == ProofStatus.VERIFIED || status == ProofStatus.SKIPPED;
    }
}
