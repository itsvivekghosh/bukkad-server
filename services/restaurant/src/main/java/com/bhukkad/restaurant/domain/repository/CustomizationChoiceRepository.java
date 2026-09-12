package com.bhukkad.restaurant.domain.repository;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import com.bhukkad.restaurant.domain.entity.CustomizationChoice;

public interface CustomizationChoiceRepository extends JpaRepository<CustomizationChoice, Long> {
    List<CustomizationChoice> findByMenuItemId(Long menuItemId);
}