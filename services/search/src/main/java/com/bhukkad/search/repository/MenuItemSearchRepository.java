package com.bhukkad.search.repository;

import com.bhukkad.search.entity.MenuItemSearchEntity;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
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
}
