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

/**
 * A customer review of a delivered order, covering the restaurant and (optionally)
 * the delivery experience.
 *
 * <h2>Moderation</h2>
 * Every review carries a {@link ModerationStatus}. Only {@code APPROVED} rows are
 * visible on public restaurant listings and only {@code APPROVED} rows contribute to
 * the aggregate restaurant rating, so an abusive review can be pulled out of both the
 * feed and the score with a single status change instead of a delete.
 *
 * <p>The {@code moderation_status} and {@code owner_response} columns are created by
 * {@code V13__growth_operations.sql} (lines 169-172) and indexed for the moderation
 * queue by {@code V17__trust_and_compliance.sql} (section 4). Because
 * {@code spring.jpa.hibernate.ddl-auto} is {@code none}, Flyway is the single source of
 * truth for the schema and the {@link Index} declarations below are documentation only.
 *
 * <p>The database default for {@code moderation_status} is {@code 'APPROVED'}, which is
 * mirrored by the Java field initialiser so that rows written before moderation existed
 * and rows written by this entity behave identically.
 */
@Entity
@Table(name = "reviews", indexes = {
        @Index(name = "idx_review_customer", columnList = "customer_id"),
        @Index(name = "idx_review_restaurant", columnList = "restaurant_id"),
        @Index(name = "idx_review_order", columnList = "order_id", unique = true),
        @Index(name = "idx_review_rating", columnList = "rating"),
        @Index(name = "idx_review_created_at", columnList = "createdAt"),
        @Index(name = "idx_review_restaurant_rating", columnList = "restaurant_id, rating"),
        @Index(name = "idx_review_restaurant_created", columnList = "restaurant_id, createdAt"),
        @Index(name = "idx_review_restaurant_moderation", columnList = "restaurant_id, moderation_status"),
        @Index(name = "idx_review_moderation_queue", columnList = "moderation_status, createdAt")
})
@Data
@NoArgsConstructor
@AllArgsConstructor
@EntityListeners(AuditingEntityListener.class)
public class Review {

    /**
     * Lifecycle of a review from the trust-and-safety point of view.
     *
     * <p>Persisted as a string so the values stay readable in SQL and stay stable if the
     * enum is reordered. Kept in sync with the {@code VARCHAR(20)} column added in
     * {@code V13__growth_operations.sql}.
     */
    public enum ModerationStatus {
        /** Awaiting a human decision; hidden from public reads and excluded from ratings. */
        PENDING,
        /** Visible publicly and counted towards the restaurant's aggregate rating. */
        APPROVED,
        /** Rejected by an admin; permanently hidden and excluded from ratings. */
        REJECTED
    }

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

    /**
     * Moderation state. Defaults to {@link ModerationStatus#APPROVED} to match the column
     * default, so enabling moderation does not retroactively hide existing content.
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "moderation_status", length = 20, nullable = false)
    private ModerationStatus moderationStatus = ModerationStatus.APPROVED;

    /**
     * Optional public reply written by the restaurant owner. Stored as {@code TEXT}
     * because owners are not length-limited the way the customer {@link #comment} is.
     */
    @Column(name = "owner_response", columnDefinition = "TEXT")
    private String ownerResponse;

    @CreatedDate
    @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt;
}