package com.bhukkad.entity;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.persistence.*;
import lombok.*;

import java.util.ArrayList;
import java.util.List;

@Entity
@Table(name = "customers")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@ToString(exclude = {"addresses", "orders", "cart"})
@EqualsAndHashCode(exclude = {"addresses", "orders", "cart"}, callSuper = true)
public class Customer extends User {

    // ---- Auth + profile (segregated onto customers per V62) ----
    @Column(nullable = true, length = 100)
    private String email;

    @JsonIgnore
    @Column(nullable = true, length = 255)
    private String password;

    @Column(nullable = true, length = 100)
    private String fullName;

    @Column(length = 15)
    private String phoneNumber;

    @Column(name = "profile_image_url", length = 500)
    private String profileImageUrl;

    /** Base32-encoded TOTP secret for multi-factor auth; null when not enrolled. */
    @JsonIgnore
    @Column(name = "totp_secret")
    private String totpSecret;

    /** Customer who referred this account; mirrors the legacy users.referrer_id. */
    @Column(name = "referrer_id")
    private Long referrerId;

    @JsonIgnore
    @OneToMany(mappedBy = "customer", cascade = CascadeType.ALL, fetch = FetchType.LAZY)
    private List<Address> addresses = new ArrayList<>();

    @JsonIgnore
    @OneToMany(mappedBy = "customer", cascade = CascadeType.ALL, fetch = FetchType.LAZY)
    private List<Order> orders = new ArrayList<>();

    @JsonIgnore
    @OneToOne(mappedBy = "customer", cascade = CascadeType.ALL, fetch = FetchType.LAZY)
    private Cart cart;

    @Column(nullable = false)
    private Integer loyaltyPoints = 0;

    @Column(name = "wallet_balance", nullable = false)
    private Double walletBalance = 0.0;

    @Column(name = "referral_code", unique = true, length = 20)
    private String referralCode;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "referred_by_customer_id")
    private Customer referredBy;
}