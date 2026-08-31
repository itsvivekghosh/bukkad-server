package com.bhukkad.restaurant.domain;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface CustomizationOptionRepository extends JpaRepository<CustomizationOption, Long> {
    List<CustomizationOption> findByChoiceId(Long choiceId);
}