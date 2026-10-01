package com.bhukkad.catalog.restaurant.domain.repository;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import com.bhukkad.catalog.restaurant.domain.entity.Cuisine;

public interface CuisineRepository extends JpaRepository<Cuisine, Long> {
    Optional<Cuisine> findByName(String name);
    List<Cuisine> findByActiveTrue();
}
