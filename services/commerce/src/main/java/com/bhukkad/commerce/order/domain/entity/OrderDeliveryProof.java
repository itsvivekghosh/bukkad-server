package com.bhukkad.commerce.order.domain.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * Delivery-proof state for one order — the single entity behind both proof
 * flows after the order+delivery merge.
 *
 * <p>Two shapes collided on the same {@code order_delivery_proofs} table:
 * the order module owned the OTP handshake (otp_hash / otp_issued_at /
 * otp_verified_at) and the delivery module owned the rider proof-of-delivery
 * fields (notes / signature). Both mapped the same table, so Hibernate saw two
 * entities for one table and the delivery copy also referenced {@code notes} /
 * {@code signature} columns that no migration had ever created.
 *
 * <p>This class is the union, and migration V25 adds the two missing columns,
 * so both features persist correctly against one row per order.
 *
 * <p>The OTP itself is never stored — only its hash, so a DB dump cannot reveal
 * live handover codes.
 */
@Entity
@Table(name = "order_delivery_proofs")
@Getter
@Setter
public class OrderDeliveryProof {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "order_id", nullable = false, unique = true)
    private Long orderId;

    /** SHA-256 of the handover OTP. Never the plaintext code. */
    @Column(name = "otp_hash", length = 128)
    private String otpHash;

    @Column(name = "otp_issued_at")
    private LocalDateTime otpIssuedAt;

    @Column(name = "otp_verified_at")
    private LocalDateTime otpVerifiedAt;

    @Column(name = "photo_url", length = 500)
    private String photoUrl;

    /** Rider-supplied delivery notes (from the delivery module's proof flow). */
    @Column(name = "notes", length = 500)
    private String notes;

    /** Rider signature captured at handover (from the delivery module's proof flow). */
    @Column(name = "signature", length = 500)
    private String signature;

    @Column(name = "created_at")
    private LocalDateTime createdAt = LocalDateTime.now();
}
