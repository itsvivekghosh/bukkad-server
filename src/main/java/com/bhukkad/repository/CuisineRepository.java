package com.bhukkad.repository;

import com.bhukkad.entity.Cuisine;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface CuisineRepository extends JpaRepository<Cuisine, Long> {
    Optional<Cuisine> findByName(String name);
    List<Cuisine> findByActiveTrue();
}