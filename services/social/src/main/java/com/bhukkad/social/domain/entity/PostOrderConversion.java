package com.bhukkad.social.domain.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * Tracks conversions from social posts to orders for analytics and optimization.
 *
 * <p>Populated when a user creates an order from a social post via the
 * order-from-post flow.</p>
 */
@Entity
@Table(name = "post_order_conversions", indexes = {
        @Index(name = "idx_post_order_conversions_post_timestamp", columnList = "post_id, conversion_timestamp DESC"),
        @Index(name = "idx_post_order_conversions_restaurant_timestamp", columnList = "restaurant_id, conversion_timestamp DESC"),
        @Index(name = "idx_post_order_conversions_user_timestamp", columnList = "user_id, conversion_timestamp DESC")
})
@Getter
@Setter
public class PostOrderConversion {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long postId;

    @Column(nullable = false)
    private Long orderId;

    @Column(nullable = false)
    private Long userId;

    @Column(nullable = false)
    private Long restaurantId;

    @Column(nullable = false)
    private LocalDateTime conversionTimestamp = LocalDateTime.now();

    // Denormalized post fields
    @Column(name = "post_author_id")
    private Long postAuthorId;

    @Column(name = "post_content", length = 2000)
    private String postContent;

    @Column(name = "post_media_urls", columnDefinition = "text[]")
    private String[] postMediaUrls;

    @Column(name = "post_post_type", length = 50)
    private String postPostType;

    @Column(nullable = false)
    private Integer itemsCount = 0;

    @Column(nullable = false, precision = 15, scale = 2)
    private BigDecimal totalAmount = BigDecimal.ZERO;
}
