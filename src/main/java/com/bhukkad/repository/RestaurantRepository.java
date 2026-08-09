package com.bhukkad.repository;

import com.bhukkad.entity.Restaurant;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface RestaurantRepository extends JpaRepository<Restaurant, Long> {
    List<Restaurant> findByIsActiveTrue();

    List<Restaurant> findByOwnerId(Long ownerId);

    @Query("SELECT r FROM Restaurant r WHERE r.isActive = true AND " +
            "(:cuisineId IS NULL OR EXISTS (SELECT c FROM r.cuisines c WHERE c.id = :cuisineId)) AND " +
            "(:isPureVeg IS NULL OR r.isPureVeg = :isPureVeg)")
    List<Restaurant> findByFilters(@Param("cuisineId") Long cuisineId,
                                   @Param("isPureVeg") Boolean isPureVeg);

    @Query("SELECT r FROM Restaurant r WHERE r.isActive = true AND " +
            "LOWER(r.name) LIKE LOWER(CONCAT('%', :keyword, '%'))")
    List<Restaurant> searchByName(@Param("keyword") String keyword);
}