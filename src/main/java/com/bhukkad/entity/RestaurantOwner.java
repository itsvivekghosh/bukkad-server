package com.bhukkad.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.List;

@Entity
@Table(name = "restaurant_owners", indexes = {
        @Index(name = "idx_owner_verified", columnList = "verified"),
        @Index(name = "idx_owner_license", columnList = "businessLicense")
})
@Data
@EqualsAndHashCode(callSuper = true)
@NoArgsConstructor
@AllArgsConstructor
public class RestaurantOwner extends User {

    @OneToMany(mappedBy = "owner", cascade = CascadeType.ALL)
    private List<Restaurant> restaurants = new ArrayList<>();

    private String businessLicense;

    @Column(nullable = false)
    private Boolean verified = false;
}