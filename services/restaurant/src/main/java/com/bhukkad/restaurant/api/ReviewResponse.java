package com.bhukkad.restaurant.api;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ReviewResponse {
    private Long id;
    private Long restaurantId;
    private Long customerId;
    private Integer rating;
    private String comment;
    private Integer foodRating;
    private Integer deliveryRating;
    private List<String> images;
    private String moderationStatus;
    private String ownerResponse;
    private LocalDateTime createdAt;
}
