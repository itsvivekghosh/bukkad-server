package com.bhukkad.search.repository;

import com.bhukkad.search.entity.MenuItemSearchEntity;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface MenuItemSearchRepository extends JpaRepository<MenuItemSearchEntity, Long> {

    // The document key in menu_item_search is the menu item id itself (same
    // value), so a second "itemId" property does not exist; callers use
    // findById(menuItemId). A derived findByItemId would break repository
    // bootstrap at startup.

    @Query("""
            SELECT m FROM MenuItemSearchEntity m
            WHERE lower(m.name) LIKE CONCAT('%', :term, '%') ESCAPE '\\'
               OR lower(m.description) LIKE CONCAT('%', :term, '%') ESCAPE '\\'
               OR lower(m.categoryName) LIKE CONCAT('%', :term, '%') ESCAPE '\\'
               OR lower(m.foodType) LIKE CONCAT('%', :term, '%') ESCAPE '\\'
            """)
    List<MenuItemSearchEntity> searchText(@Param("term") String lowercasedEscapedTerm, Pageable pageable);

    @Query("""
            SELECT m FROM MenuItemSearchEntity m
            WHERE lower(m.name) LIKE CONCAT(:prefix, '%') ESCAPE '\\'
            """)
    List<MenuItemSearchEntity> searchNamePrefix(@Param("prefix") String lowercasedEscapedPrefix, Pageable pageable);

    /**
     * P-08 OPTION (P3), off by default: word-similarity ranking over the same
     * columns as {@link #searchText} (see
     * {@code RestaurantSearchRepository#searchTextFuzzy} for gate and
     * ranking semantics); {@code term} is plain lowercased text, no LIKE
     * escaping applies.
     */
    @Query(value = """
            SELECT m.* FROM menu_item_search m
            WHERE word_similarity(:term, m.name) > :threshold
               OR word_similarity(:term, m.description) > :threshold
               OR word_similarity(:term, m.category_name) > :threshold
               OR word_similarity(:term, m.food_type) > :threshold
            ORDER BY GREATEST(word_similarity(:term, m.name),
                              word_similarity(:term, m.description),
                              word_similarity(:term, m.category_name),
                              word_similarity(:term, m.food_type)) DESC
            """, nativeQuery = true)
    List<MenuItemSearchEntity> searchTextFuzzy(@Param("term") String lowercasedTerm,
                                               @Param("threshold") double threshold,
                                               Pageable pageable);

    /**
     * ADR-002 search sync: idempotent upsert of the full document keyed by
     * the menu-item id (the projection PK IS the source id). {@code COALESCE}
     * preserves enrichment columns the event payload does not carry
     * (restaurant_distance_km), mirroring {@code indexMenuItem}.
     */
    @Modifying
    @Query(value = """
            INSERT INTO menu_item_search
                (id, restaurant_id, name, description, price, original_price,
                 discount_percentage, available, food_type, is_veg, image_url,
                 preparation_time, bestseller, restaurant_name)
            VALUES (:id, :restaurantId, :name, :description, :price, :originalPrice,
                    :discountPercentage, :available, :foodType, :isVeg, :imageUrl,
                    :preparationTime, :bestseller, :restaurantName)
            ON CONFLICT (id) DO UPDATE SET
                restaurant_id = EXCLUDED.restaurant_id,
                name = EXCLUDED.name,
                description = EXCLUDED.description,
                price = EXCLUDED.price,
                original_price = EXCLUDED.original_price,
                discount_percentage = EXCLUDED.discount_percentage,
                available = EXCLUDED.available,
                food_type = EXCLUDED.food_type,
                is_veg = EXCLUDED.is_veg,
                image_url = EXCLUDED.image_url,
                preparation_time = EXCLUDED.preparation_time,
                bestseller = EXCLUDED.bestseller,
                restaurant_name = COALESCE(EXCLUDED.restaurant_name, menu_item_search.restaurant_name)
            """, nativeQuery = true)
    int upsertFromEvent(@Param("id") Long id,
                        @Param("restaurantId") Long restaurantId,
                        @Param("name") String name,
                        @Param("description") String description,
                        @Param("price") Double price,
                        @Param("originalPrice") Double originalPrice,
                        @Param("discountPercentage") Double discountPercentage,
                        @Param("available") Boolean available,
                        @Param("foodType") String foodType,
                        @Param("isVeg") Boolean isVeg,
                        @Param("imageUrl") String imageUrl,
                        @Param("preparationTime") Integer preparationTime,
                        @Param("bestseller") Boolean bestseller,
                        @Param("restaurantName") String restaurantName);

    /** ADR-002 delete propagation: remove the projection row (JPA deleteById is inherently modifying). */
    void deleteById(Long id);

    /** Reconciliation sweep: ids still projected for a restaurant. */
    @Query("SELECT m.id FROM MenuItemSearchEntity m WHERE m.restaurantId = :restaurantId")
    List<Long> findIdsByRestaurantId(@Param("restaurantId") Long restaurantId);

    /** Reconciliation sweep: existence probe (count → 0/1). */
    int countById(Long id);

    /**
     * Reconciliation repair from the source menu snapshot: same idempotent
     * upsert keyed by id, but the enrichment columns the snapshot does not
     * carry (category/food type/images…) are preserved via COALESCE so a
     * repair never erases richer data written by {@code menu_item_changed}.
     */
    @Modifying
    @Query(value = """
            INSERT INTO menu_item_search
                (id, restaurant_id, name, description, price, available, restaurant_name)
            VALUES (:id, :restaurantId, :name, :description, :price, :available, :restaurantName)
            ON CONFLICT (id) DO UPDATE SET
                restaurant_id = EXCLUDED.restaurant_id,
                name = EXCLUDED.name,
                description = EXCLUDED.description,
                price = EXCLUDED.price,
                available = EXCLUDED.available,
                restaurant_name = COALESCE(EXCLUDED.restaurant_name, menu_item_search.restaurant_name)
            """, nativeQuery = true)
    int repairFromSource(@Param("id") Long id,
                         @Param("restaurantId") Long restaurantId,
                         @Param("name") String name,
                         @Param("description") String description,
                         @Param("price") Double price,
                         @Param("available") Boolean available,
                         @Param("restaurantName") String restaurantName);
}
