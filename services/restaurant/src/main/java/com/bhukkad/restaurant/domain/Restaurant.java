package com.bhukkad.restaurant.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import java.time.LocalDateTime;

@Entity
@Table(name = "restaurants", indexes = {
        @Index(name = "idx_restaurant_cuisine_active", columnList = "cuisineId, isActive"),
        @Index(name = "idx_restaurant_name", columnList = "name")
})
@EntityListeners(AuditingEntityListener.class)
@Getter
@Setter
public class Restaurant {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 200)
    private String name;

    private String description;

    @Column(nullable = false)
    private Long cuisineId;

    private String address;

    @Column(length = 20)
    private String phone;

    @Column(nullable = false)
    private Boolean isActive = true;

    @Column(nullable = false)
    private Double avgRating = 0.0;

    @CreatedDate
    @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @LastModifiedDate
    @Column(nullable = false)
    private LocalDateTime updatedAt;
}