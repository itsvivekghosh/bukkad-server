package com.bhukkad.social.domain.entity;

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

/**
 * SocialPost entity for the social feed service.
 *
 * <p>Geospatial queries use latitude/longitude columns with a Haversine
 * formula approximation for radius searches, avoiding the need for PostGIS.</p>
 */
@Entity
@Table(name = "social_posts", indexes = {
        @Index(name = "idx_social_posts_restaurant_created", columnList = "restaurant_id, created_at DESC"),
        @Index(name = "idx_social_posts_author_created", columnList = "author_id, created_at DESC"),
        @Index(name = "idx_social_posts_status_deleted", columnList = "status, deleted_at")
})
@EntityListeners(AuditingEntityListener.class)
@Getter
@Setter
public class SocialPost {

    public static final String STATUS_ACTIVE = "active";
    public static final String STATUS_ARCHIVED = "archived";
    public static final String STATUS_DELETED = "deleted";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long restaurantId;

    @Column(nullable = false)
    private Long authorId;

    @Column(nullable = false, length = 2000)
    private String content;

    @Column(columnDefinition = "text[]")
    private String[] mediaUrls;

    @Column(length = 50)
    private String postType = "update";

    @Column(nullable = false, length = 20)
    private String status = STATUS_ACTIVE;

    @Column(nullable = false)
    private Integer likeCount = 0;

    @Column(nullable = false)
    private Integer commentCount = 0;

    @Column
    private LocalDateTime deletedAt;

    // Denormalized for query performance
    @Column(length = 255)
    private String authorName;

    @Column(length = 255)
    private String restaurantName;

    @Column
    private Double latitude;

    @Column
    private Double longitude;

    @CreatedDate
    @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @LastModifiedDate
    @Column(nullable = false)
    private LocalDateTime updatedAt;
}
