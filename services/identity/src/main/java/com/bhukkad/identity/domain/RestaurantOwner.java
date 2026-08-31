package com.bhukkad.identity.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import java.time.LocalDateTime;

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

    @Column(name = "business_license", length = 100)
    private String businessLicense;

    @Column(nullable = false)
    private Boolean verified = false;
}