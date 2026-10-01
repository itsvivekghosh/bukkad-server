package com.bhukkad.commerce.delivery.domain.repository;
import com.bhukkad.commerce.delivery.domain.entity.CityConfig;

import org.springframework.data.jpa.repository.JpaRepository;

public interface CityConfigRepository extends JpaRepository<CityConfig, Long> {
}