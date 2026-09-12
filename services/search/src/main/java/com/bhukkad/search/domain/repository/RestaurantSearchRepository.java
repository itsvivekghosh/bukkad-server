package com.bhukkad.search.domain.repository;

import com.bhukkad.search.domain.entity.RestaurantSearchEntity;
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
     * P-08 OPTION (P3), off by default: word-similarity ranking over the same
     * columns as {@link #searchText} (<code>word_similarity</code> — the
     * per-word trigram measure pg_trgm added for exactly this search use case;
     * plain {@code similarity()} collapses on long columns and the GUC-bound
     * {@code %} operator is not per-query tunable). {@code threshold} mirrors
     * {@code app.search.fuzzy.similarity-threshold}. Only reached when
     * {@code app.search.fuzzy.enabled=true} — the LIKE path above stays the
     * shipped default per the V11 decision note. {@code term} is NOT
     * LIKE-escaped here (no pattern semantics apply to trigram matching).
     * GIN trigram indexes (V11) stay available for operator forms once
     * product tunes GUCs.
     */
    @Query(value = """
            SELECT r.* FROM restaurant_search r
            WHERE word_similarity(:term, r.name) > :threshold
               OR word_similarity(:term, r.description) > :threshold
               OR word_similarity(:term, r.cuisine_summary) > :threshold
            ORDER BY GREATEST(word_similarity(:term, r.name),
                              word_similarity(:term, r.description),
                              word_similarity(:term, r.cuisine_summary)) DESC
            """, nativeQuery = true)
    List<RestaurantSearchEntity> searchTextFuzzy(@Param("term") String lowercasedTerm,
                                                 @Param("threshold") double threshold,
                                                 Pageable pageable);

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
