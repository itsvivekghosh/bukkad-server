package com.bhukkad.search.repository;

import com.bhukkad.search.entity.RestaurantSearchEntity;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface RestaurantSearchRepository extends JpaRepository<RestaurantSearchEntity, Long> {

    /**
     * Bounded, DB-side text search (LIKE with the caller-supplied term
     * already LIKE-escaped and lowercased). The previous
     * {@code findAll().stream().filter(...contains)} pattern loaded the whole
     * projection table per public search request.
     */
    @Query("""
            SELECT r FROM RestaurantSearchEntity r
            WHERE lower(r.name) LIKE CONCAT('%', :term, '%') ESCAPE '\\'
               OR lower(r.description) LIKE CONCAT('%', :term, '%') ESCAPE '\\'
               OR lower(r.cuisineSummary) LIKE CONCAT('%', :term, '%') ESCAPE '\\'
            """)
    List<RestaurantSearchEntity> searchText(@Param("term") String lowercasedEscapedTerm, Pageable pageable);

    /** Prefix variant for autocomplete. */
    @Query("""
            SELECT r FROM RestaurantSearchEntity r
            WHERE lower(r.name) LIKE CONCAT(:prefix, '%') ESCAPE '\\'
            """)
    List<RestaurantSearchEntity> searchNamePrefix(@Param("prefix") String lowercasedEscapedPrefix, Pageable pageable);

    /**
     * ADR-002 search sync: idempotent upsert of the restaurant projection,
     * keyed by the restaurant id (the projection PK IS the source id).
     */
    @Modifying
    @Query(value = """
            INSERT INTO restaurant_search
                (id, name, description, image_url, is_open, is_active,
                 average_rating, total_reviews, cuisine_summary)
            VALUES (:id, :name, :description, :imageUrl, :isOpen, :isActive,
                    :averageRating, :totalReviews, :cuisineSummary)
            ON CONFLICT (id) DO UPDATE SET
                name = EXCLUDED.name,
                description = EXCLUDED.description,
                image_url = EXCLUDED.image_url,
                is_open = EXCLUDED.is_open,
                is_active = EXCLUDED.is_active,
                average_rating = EXCLUDED.average_rating,
                total_reviews = EXCLUDED.total_reviews
            """, nativeQuery = true)
    int upsertFromEvent(@Param("id") Long id,
                        @Param("name") String name,
                        @Param("description") String description,
                        @Param("imageUrl") String imageUrl,
                        @Param("isOpen") Boolean isOpen,
                        @Param("isActive") Boolean isActive,
                        @Param("averageRating") Double averageRating,
                        @Param("totalReviews") Integer totalReviews,
                        @Param("cuisineSummary") String cuisineSummary);
}
