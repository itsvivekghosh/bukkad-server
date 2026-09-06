package com.bhukkad.search.repository;

import com.bhukkad.search.entity.MenuItemSearchEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface MenuItemSearchRepository extends JpaRepository<MenuItemSearchEntity, Long> {
}