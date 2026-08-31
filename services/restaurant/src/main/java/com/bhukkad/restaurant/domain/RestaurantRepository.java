package com.bhukkad.restaurant.domain;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface RestaurantRepository extends JpaRepository<Restaurant, Long> {
    List<Restaurant> findByCuisineIdAndIsActiveTrue(Long cuisineId);
    List<Restaurant> findByNameContainingIgnoreCaseAndIsActiveTrue(String name);

    /** PG port of the MySQL FULLTEXT restaurant-name search (tsvector). */
    @Query(value = """
            SELECT * FROM restaurants
            WHERE search_vector @@ plainto_tsquery('english', :keyword)
            ORDER BY ts_rank(search_vector, plainto_tsquery('english', :keyword)) DESC
            LIMIT :limit
            """, nativeQuery = true)
    List<Restaurant> fullTextSearchByName(@Param("keyword") String keyword, @Param("limit") int limit);
}