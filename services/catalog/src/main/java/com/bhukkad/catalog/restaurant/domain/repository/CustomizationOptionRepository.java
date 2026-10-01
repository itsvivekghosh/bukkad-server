package com.bhukkad.catalog.restaurant.domain.repository;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import com.bhukkad.catalog.restaurant.domain.entity.CustomizationOption;

public interface CustomizationOptionRepository extends JpaRepository<CustomizationOption, Long> {
    List<CustomizationOption> findByChoiceId(Long choiceId);
}