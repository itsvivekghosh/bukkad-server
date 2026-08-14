package com.bhukkad.entity;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

@Entity
@Table(name = "reviews", indexes = {
        @Index(name = "idx_review_customer", columnList = "customer_id"),
        @Index(name = "idx_review_restaurant", columnList = "restaurant_id"),
        @Index(name = "idx_review_order", columnList = "order_id", unique = true),
        @Index(name = "idx_review_rating", columnList = "rating"),
        @Index(name = "idx_review_created_at", columnList = "createdAt"),
        @Index(name = "idx_review_restaurant_rating", columnList = "restaurant_id, rating"),
        @Index(name = "idx_review_restaurant_created", columnList = "restaurant_id, createdAt")
})
@Data
@NoArgsConstructor
@AllArgsConstructor
@EntityListeners(AuditingEntityListener.class)
public class Review {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "customer_id", nullable = false)
    @JsonIgnoreProperties({"password", "hibernateLazyInitializer", "handler"})
    private Customer customer;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "restaurant_id", nullable = false)
    @JsonIgnoreProperties({"owner", "hibernateLazyInitializer", "handler"})
    private Restaurant restaurant;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "order_id", nullable = false)
    @JsonIgnoreProperties({
            "orderItems",
            "deliveryAddress",
            "deliveryAgent",
            "payment",
            "appliedCoupon",
            "customer",
            "restaurant",
            "hibernateLazyInitializer",
            "handler"
    })
    private Order order;

    @Column(nullable = false)
    private Integer rating; // 1-5

    @Column(length = 1000)
    private String comment;

    private Integer foodRating;

    private Integer deliveryRating;

    @ElementCollection(fetch = FetchType.EAGER)
    private List<String> images = new ArrayList<>();

    @CreatedDate
    @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt;
}