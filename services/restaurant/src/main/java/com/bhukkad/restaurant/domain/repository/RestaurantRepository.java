package com.bhukkad.restaurant.domain.repository;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import com.bhukkad.restaurant.domain.entity.Restaurant;

public interface RestaurantRepository extends JpaRepository<Restaurant, Long> {
    List<Restaurant> findByIsActiveTrue();

    List<Restaurant> findByIsActiveTrueAndIsOpenTrue();

    long countByIsActiveTrue();

    List<Restaurant> findByCuisineIdAndIsActiveTrue(Long cuisineId);
    List<Restaurant> findByOwnerId(Long ownerId);
    List<Restaurant> findByNameContainingIgnoreCaseAndIsActiveTrue(String name);

    /** PG port of the MySQL FULLTEXT restaurant-name search (tsvector). */
    @Query(value = """
            SELECT * FROM restaurants
            WHERE search_vector @@ plainto_tsquery('english', :keyword)
            ORDER BY ts_rank(search_vector, plainto_tsquery('english', :keyword)) DESC
            LIMIT :limit
            """, nativeQuery = true)
    List<Restaurant> fullTextSearchByName(@Param("keyword") String keyword, @Param("limit") int limit);

    @Query("SELECT r.id, r.name FROM Restaurant r WHERE r.isActive = true")
    List<Object[]> findActiveRestaurantNames();

    Page<Restaurant> findByIsActive(Boolean active, Pageable pageable);

    @Query("SELECT r FROM Restaurant r WHERE r.isActive = true AND " +
            "LOWER(r.name) LIKE LOWER(CONCAT('%', :keyword, '%'))")
    List<Restaurant> searchByName(@Param("keyword") String keyword);
}
