package com.bhukkad.search.repository;

import com.bhukkad.search.entity.RestaurantSearchEntity;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
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
}
