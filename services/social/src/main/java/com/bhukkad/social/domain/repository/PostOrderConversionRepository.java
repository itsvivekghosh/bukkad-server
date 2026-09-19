package com.bhukkad.social.domain.repository;

import com.bhukkad.social.domain.entity.PostOrderConversion;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;

@Repository
public interface PostOrderConversionRepository extends JpaRepository<PostOrderConversion, Long> {

    List<PostOrderConversion> findByPostIdOrderByConversionTimestampDesc(Long postId);

    List<PostOrderConversion> findByRestaurantIdOrderByConversionTimestampDesc(Long restaurantId);

    List<PostOrderConversion> findByUserIdOrderByConversionTimestampDesc(Long userId);

    long countByRestaurantIdAndConversionTimestampBetween(Long restaurantId, LocalDateTime start, LocalDateTime end);
}
