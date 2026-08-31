package com.bhukkad.entity;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.List;

@Entity
@Table(name = "delivery_agents", indexes = {
        @Index(name = "idx_agent_available", columnList = "available"),
        @Index(name = "idx_agent_verified", columnList = "verified"),
        @Index(name = "idx_agent_available_verified", columnList = "available, verified"),
        @Index(name = "idx_agent_location", columnList = "currentLatitude, currentLongitude"),
        @Index(name = "idx_agent_rating", columnList = "averageRating"),
        @Index(name = "idx_agent_deliveries", columnList = "totalDeliveries")
})
@Data
@EqualsAndHashCode(callSuper = true)
@NoArgsConstructor
@AllArgsConstructor
public class DeliveryAgent extends User {

    // ---- Auth + profile (segregated onto delivery_agents per V62) ----
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

    private String vehicleType;

    private String vehicleNumber;

    private String licenseNumber;

    @Column(nullable = false)
    private Boolean available = true;

    @Column(nullable = false)
    private Boolean verified = false;

    private Double currentLatitude;

    private Double currentLongitude;

    @JsonIgnore
    @OneToMany(mappedBy = "deliveryAgent")
    private List<Order> deliveries = new ArrayList<>();

    private Double averageRating = 0.0;

    private Integer totalDeliveries = 0;
}