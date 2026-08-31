package com.bhukkad.identity.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import java.time.LocalDateTime;

@Entity
@Table(name = "delivery_agents", indexes = {
        @Index(name = "idx_agent_available_verified", columnList = "available, verified"),
        @Index(name = "idx_agent_location", columnList = "currentLatitude, currentLongitude")
})
@Getter
@Setter
public class DeliveryAgent extends User {

    @Column(length = 100)
    private String email;

    @Column(name = "full_name", length = 100)
    private String fullName;

    @Column(name = "phone_number", length = 15)
    private String phoneNumber;

    @Column(nullable = false)
    private Boolean available = false;

    @Column(nullable = false)
    private Boolean verified = false;

    @Column(name = "current_latitude")
    private Double currentLatitude;

    @Column(name = "current_longitude")
    private Double currentLongitude;

    @Column(name = "average_rating", nullable = false)
    private Double averageRating = 0.0;

    @Column(name = "total_deliveries", nullable = false)
    private Long totalDeliveries = 0L;
}