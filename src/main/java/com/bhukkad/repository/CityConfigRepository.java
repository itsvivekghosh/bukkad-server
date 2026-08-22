package com.bhukkad.repository;

import com.bhukkad.entity.CityConfig;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface CityConfigRepository extends JpaRepository<CityConfig, Long> {

    Optional<CityConfig> findByCityIgnoreCase(String city);

    boolean existsByCityIgnoreCase(String city);

    List<CityConfig> findByIsActiveTrueOrderByCityAsc();
}
