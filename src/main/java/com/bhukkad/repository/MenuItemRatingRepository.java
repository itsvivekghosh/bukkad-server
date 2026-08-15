package com.bhukkad.repository;

import com.bhukkad.entity.MenuItemRating;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface MenuItemRatingRepository extends JpaRepository<MenuItemRating, Long> {
    Optional<MenuItemRating> findByOrderIdAndMenuItemId(Long orderId, Long menuItemId);

    List<MenuItemRating> findByMenuItemIdOrderByCreatedAtDesc(Long menuItemId);

    @Query("SELECT AVG(r.rating) FROM MenuItemRating r WHERE r.menuItem.id = :menuItemId")
    Double getAverageRatingByMenuItem(@Param("menuItemId") Long menuItemId);

    @Query("SELECT COUNT(r) FROM MenuItemRating r WHERE r.menuItem.id = :menuItemId")
    long countByMenuItem(@Param("menuItemId") Long menuItemId);
}
