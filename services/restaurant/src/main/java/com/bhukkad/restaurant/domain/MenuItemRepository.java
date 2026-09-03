package com.bhukkad.restaurant.domain;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface MenuItemRepository extends JpaRepository<MenuItem, Long> {
    List<MenuItem> findByRestaurantIdAndIsAvailableTrue(Long restaurantId);
    List<MenuItem> findByRestaurantId(Long restaurantId);
    List<MenuItem> findByCategoryId(Long categoryId);
    int countByCategoryId(Long categoryId);

    /** PG port of the MySQL FULLTEXT search: tsvector + plainto_tsquery. */
    @Query(value = """
            SELECT * FROM menu_items
            WHERE search_vector @@ plainto_tsquery('english', :keyword)
            ORDER BY ts_rank(search_vector, plainto_tsquery('english', :keyword)) DESC
            LIMIT :limit
            """, nativeQuery = true)
    List<MenuItem> fullTextSearch(@Param("keyword") String keyword, @Param("limit") int limit);
}