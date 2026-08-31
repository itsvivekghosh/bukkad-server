package com.bhukkad.entity;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.persistence.*;
import lombok.*;

import java.util.ArrayList;
import java.util.List;

@Entity
@Table(name = "restaurant_owners", indexes = {
        @Index(name = "idx_owner_verified", columnList = "verified"),
        @Index(name = "idx_owner_license", columnList = "businessLicense")
})
@Data
@NoArgsConstructor
@AllArgsConstructor
@ToString(exclude = {"restaurants"})
@EqualsAndHashCode(exclude = {"restaurants"}, callSuper = true)
public class RestaurantOwner extends User {

    // ---- Auth + profile (segregated onto restaurant_owners per V62) ----
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

    @JsonIgnore
    @OneToMany(mappedBy = "owner", cascade = CascadeType.ALL, fetch = FetchType.LAZY)
    private List<Restaurant> restaurants = new ArrayList<>();

    @Column(length = 100)
    private String businessLicense;

    @Column(nullable = false)
    private Boolean verified = false;
}