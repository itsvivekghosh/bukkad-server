package com.bhukkad.search.repository;

import com.bhukkad.search.entity.RestaurantSearchEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface RestaurantSearchRepository extends JpaRepository<RestaurantSearchEntity, Long> {
}