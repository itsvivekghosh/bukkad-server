package com.bhukkad.restaurant.domain.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import com.bhukkad.restaurant.domain.entity.MenuItem;

public interface MenuItemRepository extends JpaRepository<MenuItem, Long> {

    List<MenuItem> findByRestaurantIdAndIsAvailableTrue(Long restaurantId);
    List<MenuItem> findByRestaurantId(Long restaurantId);
    List<MenuItem> findByCategoryId(Long categoryId);
    int countByCategoryId(Long categoryId);

    Optional<MenuItem> findByIdAndRestaurantId(Long id, Long restaurantId);

    List<MenuItem> findByCategoryIdAndIsAvailableTrue(Long categoryId);

    @Query("SELECT m FROM MenuItem m WHERE m.categoryId = :categoryId AND m.isAvailable = true AND m.bestseller = true")
    List<MenuItem> findBestsellersByRestaurant(@Param("categoryId") Long categoryId);

    @Query("SELECT m FROM MenuItem m WHERE m.isAvailable = true AND LOWER(m.name) LIKE LOWER(CONCAT('%', :keyword, '%'))")
    List<MenuItem> searchByName(@Param("keyword") String keyword);

    /** PG port of the MySQL FULLTEXT search: tsvector + plainto_tsquery. */
    @Query(value = """
            SELECT * FROM menu_items
            WHERE search_vector @@ plainto_tsquery('english', :keyword)
            ORDER BY ts_rank(search_vector, plainto_tsquery('english', :keyword)) DESC
            LIMIT :limit
            """, nativeQuery = true)
    List<MenuItem> fullTextSearch(@Param("keyword") String keyword, @Param("limit") int limit);

    @Query("SELECT m FROM MenuItem m WHERE m.restaurantId = :restaurantId AND m.isAvailable = true AND m.stockQuantity IS NOT NULL AND m.stockQuantity <= :threshold ORDER BY m.stockQuantity ASC, m.name")
    List<MenuItem> findLowStockByRestaurant(@Param("restaurantId") Long restaurantId, @Param("threshold") int threshold);

    @Modifying
    @Query("""
            UPDATE MenuItem m
            SET m.stockQuantity = m.stockQuantity - :quantity,
                m.updatedAt = CURRENT_TIMESTAMP
            WHERE m.id = :id AND m.stockQuantity IS NOT NULL AND m.stockQuantity >= :quantity
            """)
    int decrementStockAtomic(@Param("id") Long id, @Param("quantity") int quantity);

    @Modifying
    @Query("""
            UPDATE MenuItem m
            SET m.stockQuantity = COALESCE(m.stockQuantity, 0) + :quantity,
                m.updatedAt = CURRENT_TIMESTAMP
            WHERE m.id = :id
            """)
    int restoreStockAtomic(@Param("id") Long id, @Param("quantity") int quantity);

    @Query("SELECT m.id, m.name FROM MenuItem m WHERE m.isAvailable = true")
    List<Object[]> findAvailableMenuItemNames();
}
