package com.bhukkad.order.domain.entity;

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
 * Delivery-proof handshake state (OTP issue/verify + optional photo URL) for
 * one order. The OTP itself is never stored — only its hash, so a DB dump
 * cannot reveal live handover codes.
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

    @Column(name = "otp_hash", length = 128)
    private String otpHash;

    @Column(name = "otp_issued_at")
    private LocalDateTime otpIssuedAt;

    @Column(name = "otp_verified_at")
    private LocalDateTime otpVerifiedAt;

    @Column(name = "photo_url", length = 500)
    private String photoUrl;

    @Column(name = "created_at")
    private LocalDateTime createdAt = LocalDateTime.now();
}
