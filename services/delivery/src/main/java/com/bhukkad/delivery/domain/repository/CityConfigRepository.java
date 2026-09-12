package com.bhukkad.delivery.domain.repository;
import com.bhukkad.delivery.domain.entity.CityConfig;

import org.springframework.data.jpa.repository.JpaRepository;

public interface CityConfigRepository extends JpaRepository<CityConfig, Long> {
}