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

    @JsonIgnore
    @OneToMany(mappedBy = "owner", cascade = CascadeType.ALL, fetch = FetchType.LAZY)
    private List<Restaurant> restaurants = new ArrayList<>();

    @Column(length = 100)
    private String businessLicense;

    @Column(nullable = false)
    private Boolean verified = false;
}