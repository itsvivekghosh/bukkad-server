package com.bhukkad.repository;

import com.bhukkad.entity.MenuItem;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface MenuItemRepository extends JpaRepository<MenuItem, Long> {

    // With JOIN FETCH for category
    @Query("SELECT m FROM MenuItem m " +
            "JOIN FETCH m.category c " +
            "JOIN FETCH c.restaurant r " +
            "LEFT JOIN FETCH r.owner " +
            "WHERE m.id = :id")
    Optional<MenuItem> findByIdWithDetails(@Param("id") Long id);

    @Query("SELECT m FROM MenuItem m " +
            "JOIN FETCH m.category c " +
            "WHERE c.id = :categoryId AND m.available = true " +
            "ORDER BY m.name")
    List<MenuItem> findByCategoryIdWithDetails(@Param("categoryId") Long categoryId);

    @Query("SELECT m FROM MenuItem m " +
            "JOIN FETCH m.category c " +
            "WHERE c.restaurant.id = :restaurantId AND m.available = true " +
            "ORDER BY c.displayOrder, m.name")
    List<MenuItem> findByRestaurantIdWithDetails(@Param("restaurantId") Long restaurantId);

    @Query("SELECT m FROM MenuItem m " +
            "JOIN FETCH m.category c " +
            "WHERE c.restaurant.id = :restaurantId AND m.available = true AND m.bestseller = true")
    List<MenuItem> findBestsellersWithDetails(@Param("restaurantId") Long restaurantId);

    @Query("SELECT m FROM MenuItem m " +
            "JOIN FETCH m.category c " +
            "WHERE m.available = true AND LOWER(m.name) LIKE LOWER(CONCAT('%', :keyword, '%'))")
    List<MenuItem> searchByNameWithDetails(@Param("keyword") String keyword);

    // Simple queries (without JOIN FETCH)
    List<MenuItem> findByCategoryIdAndAvailableTrue(Long categoryId);

    List<MenuItem> findByCategoryRestaurantIdAndAvailableTrue(Long restaurantId);

    @Query("SELECT m FROM MenuItem m WHERE m.category.restaurant.id = :restaurantId AND m.available = true AND m.bestseller = true")
    List<MenuItem> findBestsellersByRestaurant(@Param("restaurantId") Long restaurantId);

    @Query("SELECT m FROM MenuItem m WHERE m.available = true AND LOWER(m.name) LIKE LOWER(CONCAT('%', :keyword, '%'))")
    List<MenuItem> searchByName(@Param("keyword") String keyword);

    @Query(value = """
            SELECT m.* FROM menu_items m
            WHERE m.available = 1
            AND MATCH(m.name, m.description) AGAINST(:keyword IN NATURAL LANGUAGE MODE)
            ORDER BY MATCH(m.name, m.description) AGAINST(:keyword IN NATURAL LANGUAGE MODE) DESC
            LIMIT 100
            """, nativeQuery = true)
    List<MenuItem> fullTextSearch(@Param("keyword") String keyword);

    @Query("SELECT m FROM MenuItem m " +
            "JOIN FETCH m.category c " +
            "WHERE c.restaurant.id = :restaurantId AND m.available = true " +
            "AND m.stockQuantity IS NOT NULL AND m.stockQuantity <= :threshold " +
            "ORDER BY m.stockQuantity ASC, m.name")
    List<MenuItem> findLowStockByRestaurant(@Param("restaurantId") Long restaurantId,
                                            @Param("threshold") int threshold);

    int countByCategoryId(Long categoryId);

    /**
     * Compact id/name pairs for all available menu items, used to build the
     * in-memory autocomplete trie without hydrating full entities.
     */
    @Query("SELECT m.id, m.name FROM MenuItem m WHERE m.available = true")
    List<Object[]> findAvailableMenuItemNames();
}