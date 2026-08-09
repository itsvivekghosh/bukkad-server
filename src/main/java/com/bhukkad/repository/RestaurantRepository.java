package com.bhukkad.repository;

import com.bhukkad.entity.Restaurant;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface RestaurantRepository extends JpaRepository<Restaurant, Long> {

    List<Restaurant> findByIsActiveTrue();

    List<Restaurant> findByOwnerId(Long ownerId);

    // ==================== JOIN FETCH Queries ====================

    @Query("SELECT DISTINCT r FROM Restaurant r " +
            "LEFT JOIN FETCH r.address " +
            "LEFT JOIN FETCH r.cuisines " +
            "LEFT JOIN FETCH r.owner " +
            "WHERE r.id = :id")
    Optional<Restaurant> findByIdWithDetails(@Param("id") Long id);

    @Query("SELECT DISTINCT r FROM Restaurant r " +
            "LEFT JOIN FETCH r.address " +
            "LEFT JOIN FETCH r.cuisines " +
            "WHERE r.isActive = true " +
            "ORDER BY r.averageRating DESC")
    List<Restaurant> findAllActiveWithDetails();

    @Query("SELECT DISTINCT r FROM Restaurant r " +
            "LEFT JOIN FETCH r.address " +
            "LEFT JOIN FETCH r.cuisines " +
            "LEFT JOIN FETCH r.owner " +
            "WHERE r.owner.id = :ownerId " +
            "ORDER BY r.createdAt DESC")
    List<Restaurant> findByOwnerIdWithDetails(@Param("ownerId") Long ownerId);

    @Query("SELECT DISTINCT r FROM Restaurant r " +
            "LEFT JOIN FETCH r.address " +
            "LEFT JOIN FETCH r.cuisines " +
            "WHERE r.isActive = true " +
            "AND LOWER(r.name) LIKE LOWER(CONCAT('%', :keyword, '%'))")
    List<Restaurant> searchByNameWithDetails(@Param("keyword") String keyword);

    @Query("SELECT r FROM Restaurant r WHERE r.isActive = true AND " +
            "LOWER(r.name) LIKE LOWER(CONCAT('%', :keyword, '%'))")
    List<Restaurant> searchByName(@Param("keyword") String keyword);

    @Query("SELECT r FROM Restaurant r WHERE r.isActive = true AND " +
            "(:cuisineId IS NULL OR EXISTS (SELECT c FROM r.cuisines c WHERE c.id = :cuisineId)) AND " +
            "(:isPureVeg IS NULL OR r.isPureVeg = :isPureVeg)")
    List<Restaurant> findByFilters(@Param("cuisineId") Long cuisineId,
                                   @Param("isPureVeg") Boolean isPureVeg);

    // Admin queries
    Page<Restaurant> findByIsActive(Boolean active, Pageable pageable);

    List<Restaurant> findTop10ByIsActiveTrueOrderByAverageRatingDesc();
}