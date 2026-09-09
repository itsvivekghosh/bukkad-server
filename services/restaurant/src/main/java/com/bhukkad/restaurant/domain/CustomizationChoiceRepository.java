package com.bhukkad.restaurant.domain;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface CustomizationChoiceRepository extends JpaRepository<CustomizationChoice, Long> {
    List<CustomizationChoice> findByMenuItemId(Long menuItemId);
}