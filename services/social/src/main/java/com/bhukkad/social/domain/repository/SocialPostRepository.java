package com.bhukkad.social.domain.repository;

import com.bhukkad.social.domain.entity.SocialPost;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;

/**
 * SocialPost repository with geospatial queries.
 *
 * <p>Uses latitude/longitude columns with Haversine formula and bounding box
 * approaches for geospatial queries.</p>
 */
@Repository
public interface SocialPostRepository extends JpaRepository<SocialPost, Long> {

    List<SocialPost> findByRestaurantIdAndStatusAndDeletedAtIsNullOrderByCreatedAtDesc(
            Long restaurantId, String status, org.springframework.data.domain.Pageable pageable);

    List<SocialPost> findByAuthorIdAndStatusAndDeletedAtIsNullOrderByCreatedAtDesc(
            Long authorId, String status, org.springframework.data.domain.Pageable pageable);

    List<SocialPost> findByStatusAndDeletedAtIsNullAndCreatedAtGreaterThanOrderByCreatedAtDesc(
            String status, LocalDateTime since);

    /**
     * Find recent active posts since a given timestamp.
     * Used for feed generation and cache warming.
     */
    @Query("SELECT p FROM SocialPost p WHERE p.status = :status AND p.deletedAt IS NULL " +
            "AND p.createdAt > :since ORDER BY p.createdAt DESC")
    List<SocialPost> findRecentActivePosts(@Param("status") String status,
                                           @Param("since") LocalDateTime since);

    /**
     * Find active posts within a radius using the Haversine formula.
     * Uses latitude/longitude columns with a bounding box pre-filter for efficiency.
     */
    @Query(value = """
            WITH bounds AS (
              SELECT
                :lat - (:radiusMeters / 111000.0) AS south_lat,
                :lat + (:radiusMeters / 111000.0) AS north_lat,
                :lng - (:radiusMeters / (111000.0 * COS(RADIANS(:lat)))) AS west_lng,
                :lng + (:radiusMeters / (111000.0 * COS(RADIANS(:lat)))) AS east_lng
            )
            SELECT * FROM social_posts, bounds
            WHERE status = 'active'
              AND deleted_at IS NULL
              AND latitude BETWEEN bounds.south_lat AND bounds.north_lat
              AND longitude BETWEEN bounds.west_lng AND bounds.east_lng
              AND (6371000 * ACOS(
                COS(RADIANS(:lat)) * COS(RADIANS(latitude)) *
                COS(RADIANS(longitude) - RADIANS(:lng)) +
                SIN(RADIANS(:lat)) * SIN(RADIANS(latitude))
              )) < :radiusMeters
            ORDER BY created_at DESC
            LIMIT :limit
            """, nativeQuery = true)
    List<SocialPost> findActivePostsWithinRadius(
            @Param("lat") double lat,
            @Param("lng") double lng,
            @Param("radiusMeters") double radiusMeters,
            @Param("limit") int limit);

    /**
     * Find active posts within a bounding box using latitude/longitude columns.
     */
    @Query(value = """
            SELECT * FROM social_posts
            WHERE status = 'active'
              AND deleted_at IS NULL
              AND latitude BETWEEN :southLat AND :northLat
              AND longitude BETWEEN :westLng AND :eastLng
            ORDER BY created_at DESC
            LIMIT :limit
            """, nativeQuery = true)
    List<SocialPost> findActivePostsInBounds(@Param("southLat") double southLat,
                                             @Param("westLng") double westLng,
                                             @Param("northLat") double northLat,
                                             @Param("eastLng") double eastLng,
                                             @Param("limit") int limit);
}
