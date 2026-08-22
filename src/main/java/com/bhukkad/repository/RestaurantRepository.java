package com.bhukkad.repository;

import com.bhukkad.entity.Restaurant;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface RestaurantRepository extends JpaRepository<Restaurant, Long> {

    List<Restaurant> findByIsActiveTrue();

    List<Restaurant> findByOwnerId(Long ownerId);

    // ==================== JOIN FETCH Queries ====================

    @Query("SELECT DISTINCT r FROM Restaurant r " +
            "LEFT JOIN FETCH r.address " +
            "LEFT JOIN FETCH r.cuisines " +
            "LEFT JOIN FETCH r.owner " +
            "WHERE r.id = :id")
    Optional<Restaurant> findByIdWithDetails(@Param("id") Long id);

    @Query("SELECT DISTINCT r FROM Restaurant r " +
            "LEFT JOIN FETCH r.address " +
            "LEFT JOIN FETCH r.cuisines " +
            "WHERE r.isActive = true " +
            "ORDER BY r.averageRating DESC")
    List<Restaurant> findAllActiveWithDetails();

    @Query("SELECT DISTINCT r FROM Restaurant r " +
            "LEFT JOIN FETCH r.address " +
            "LEFT JOIN FETCH r.cuisines " +
            "LEFT JOIN FETCH r.owner " +
            "WHERE r.owner.id = :ownerId " +
            "ORDER BY r.createdAt DESC")
    List<Restaurant> findByOwnerIdWithDetails(@Param("ownerId") Long ownerId);

    @Query("SELECT DISTINCT r FROM Restaurant r " +
            "LEFT JOIN FETCH r.address " +
            "LEFT JOIN FETCH r.cuisines " +
            "WHERE r.isActive = true " +
            "AND LOWER(r.name) LIKE LOWER(CONCAT('%', :keyword, '%'))")
    List<Restaurant> searchByNameWithDetails(@Param("keyword") String keyword);

    @Query("SELECT r FROM Restaurant r WHERE r.isActive = true AND " +
            "LOWER(r.name) LIKE LOWER(CONCAT('%', :keyword, '%'))")
    List<Restaurant> searchByName(@Param("keyword") String keyword);

    @Query("SELECT DISTINCT r FROM Restaurant r " +
            "LEFT JOIN FETCH r.address " +
            "LEFT JOIN FETCH r.cuisines " +
            "WHERE r.id IN (" +
            "  SELECT r2.id FROM Restaurant r2 LEFT JOIN r2.cuisines c " +
            "  WHERE r2.isActive = true " +
            "  AND (:cuisineId IS NULL OR c.id = :cuisineId) " +
            "  AND (:isPureVeg IS NULL OR r2.isPureVeg = :isPureVeg)" +
            ")")
    List<Restaurant> findByFilters(@Param("cuisineId") Long cuisineId,
                                   @Param("isPureVeg") Boolean isPureVeg);

    // Admin queries
    Page<Restaurant> findByIsActive(Boolean active, Pageable pageable);

    List<Restaurant> findTop10ByIsActiveTrueOrderByAverageRatingDesc();

    @Query(value = """
            SELECT r.id FROM restaurants r
            INNER JOIN addresses a ON r.address_id = a.id
            WHERE r.is_active = 1
            AND (6371 * acos(LEAST(1, GREATEST(-1,
                cos(radians(:lat)) * cos(radians(a.latitude))
                * cos(radians(a.longitude) - radians(:lon))
                + sin(radians(:lat)) * sin(radians(a.latitude))
            )))) <= :radiusKm
            ORDER BY (6371 * acos(LEAST(1, GREATEST(-1,
                cos(radians(:lat)) * cos(radians(a.latitude))
                * cos(radians(a.longitude) - radians(:lon))
                + sin(radians(:lat)) * sin(radians(a.latitude))
            ))))
            LIMIT :limit
            """, nativeQuery = true)
    List<Long> findNearbyRestaurantIds(
            @Param("lat") double latitude,
            @Param("lon") double longitude,
            @Param("radiusKm") double radiusKm,
            @Param("limit") int limit);

    @Query(value = """
            SELECT r.* FROM restaurants r
            WHERE r.is_active = 1
            AND MATCH(r.name) AGAINST(:keyword IN NATURAL LANGUAGE MODE)
            ORDER BY MATCH(r.name) AGAINST(:keyword IN NATURAL LANGUAGE MODE) DESC
            LIMIT 50
            """, nativeQuery = true)
    List<Restaurant> fullTextSearchByName(@Param("keyword") String keyword);

    /**
     * Compact id/name pairs for all active restaurants, used to build the
     * in-memory autocomplete trie without hydrating full entities.
     */
    @Query("SELECT r.id, r.name FROM Restaurant r WHERE r.isActive = true")
    List<Object[]> findActiveRestaurantNames();
}