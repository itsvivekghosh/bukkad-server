package com.bhukkad.identity.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

@Entity
@Table(name = "restaurant_owners", indexes = {
        @Index(name = "idx_owner_verified", columnList = "verified")
})
@Getter
@Setter
public class RestaurantOwner extends User {

    @Column(length = 100)
    private String email;

    @Column(name = "full_name", length = 100)
    private String fullName;

    @Column(name = "phone_number", length = 15)
    private String phoneNumber;

    @Column(name = "profile_image_url", length = 500)
    private String profileImageUrl;

    @Column(name = "totp_secret")
    private String totpSecret;

    @Column(name = "business_license", length = 100)
    private String businessLicense;

    @Column(nullable = false)
    private Boolean verified = false;
}