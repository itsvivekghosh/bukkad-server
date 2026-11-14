package com.bhukkad.catalog.restaurant.domain.repository;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import com.bhukkad.catalog.restaurant.domain.entity.Restaurant;

public interface RestaurantRepository extends JpaRepository<Restaurant, Long> {
    // Bounded/paginated versions (replace unbounded List methods)
    Page<Restaurant> findByIsActiveTrue(Pageable pageable);
    Page<Restaurant> findByIsActiveTrueAndIsOpenTrue(Pageable pageable);

    long countByIsActiveTrue();

    Page<Restaurant> findByCuisineIdAndIsActiveTrue(Long cuisineId, Pageable pageable);
    Page<Restaurant> findByOwnerId(Long ownerId, Pageable pageable);
    Page<Restaurant> findByNameContainingIgnoreCaseAndIsActiveTrue(String name, Pageable pageable);

    // Legacy unbounded methods - DEPRECATED, use paginated versions above
    @Deprecated
    List<Restaurant> findByIsActiveTrue();

    @Deprecated
    List<Restaurant> findByIsActiveTrueAndIsOpenTrue();

    @Deprecated
    List<Restaurant> findByCuisineIdAndIsActiveTrue(Long cuisineId);

    @Deprecated
    List<Restaurant> findByOwnerId(Long ownerId);

    @Deprecated
    List<Restaurant> findByNameContainingIgnoreCaseAndIsActiveTrue(String name);

    /** PG port of the MySQL FULLTEXT restaurant-name search (tsvector). */
    @Query(value = """
            SELECT * FROM restaurants
            WHERE search_vector @@ plainto_tsquery('english', :keyword)
            ORDER BY ts_rank(search_vector, plainto_tsquery('english', :keyword)) DESC
            """, nativeQuery = true)
    Page<Restaurant> fullTextSearchByName(@Param("keyword") String keyword, Pageable pageable);

    @Query("SELECT r.id, r.name FROM Restaurant r WHERE r.isActive = true")
    List<Object[]> findActiveRestaurantNames();

    @Query("SELECT r FROM Restaurant r WHERE r.isActive = true AND " +
            "(r.createdAt < :createdAt OR (r.createdAt = :createdAt AND r.id < :id)) " +
            "ORDER BY r.createdAt DESC, r.id DESC")
    List<Restaurant> findByIsActiveTrueCursor(@Param("createdAt") java.time.LocalDateTime createdAt,
                                                @Param("id") Long id,
                                                org.springframework.data.domain.Pageable pageable);

    Page<Restaurant> findByIsActive(Boolean active, Pageable pageable);

    @Query("SELECT r FROM Restaurant r WHERE r.isActive = true AND " +
            "LOWER(r.name) LIKE LOWER(CONCAT('%', :keyword, '%'))")
    List<Restaurant> searchByName(@Param("keyword") String keyword);

    /**
     * PostGIS-powered nearby query (Phase 1 spatial foundation).
     * Returns an array per row: [id, name, latitude, longitude, distanceMeters].
     * The caller maps these into a summary DTO.
     */
    @Query(value = """
            SELECT
                r.id,
                r.name,
                r.latitude,
                r.longitude,
                ST_Distance(r.geog, ST_MakePoint(:lng, :lat)::GEOGRAPHY) AS distance_meters
            FROM public.restaurants r
            WHERE r.is_active = true
              AND r.geog IS NOT NULL
              AND ST_DWithin(
                      r.geog,
                      ST_MakePoint(:lng, :lat)::GEOGRAPHY,
                      :radiusMeters
                  )
            ORDER BY distance_meters ASC
            LIMIT :limit
            """, nativeQuery = true)
    List<Object[]> findNearbyWithDistance(
            @Param("lat") double lat,
            @Param("lng") double lng,
            @Param("radiusMeters") double radiusMeters,
            @Param("limit") int limit
    );
}
