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