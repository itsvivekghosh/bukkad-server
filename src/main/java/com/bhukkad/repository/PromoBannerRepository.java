package com.bhukkad.repository;

import com.bhukkad.entity.PromoBanner;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;

@Repository
public interface PromoBannerRepository extends JpaRepository<PromoBanner, Long> {

    @Query("""
            SELECT b FROM PromoBanner b
            WHERE b.isActive = true
              AND (b.startsAt IS NULL OR b.startsAt <= :now)
              AND (b.endsAt IS NULL OR b.endsAt >= :now)
            ORDER BY b.displayOrder ASC
            """)
    List<PromoBanner> findActiveBanners(LocalDateTime now);
}
