package com.bhukkad.dto.response;

import com.bhukkad.entity.Review;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * Wire representation of a {@link Review}.
 *
 * <p>Review endpoints must not return the entity directly: Jackson walks the
 * lazy {@code customer}/{@code restaurant} associations and their
 * {@code @ElementCollection} fields (referral codes, features, gallery, …),
 * which throws LazyInitializationException once the session is closed
 * (open-in-view is disabled). This DTO carries exactly the fields the
 * customer app reads.</p>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ReviewResponse {

    private Long id;
    private CustomerBrief customer;
    private RestaurantBrief restaurant;
    private OrderBrief order;
    private Integer rating;
    private String comment;
    private Integer foodRating;
    private Integer deliveryRating;
    private List<String> images;
    private String moderationStatus;
    private String ownerResponse;
    private java.time.LocalDateTime createdAt;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class CustomerBrief {
        private Long id;
        private String fullName;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class RestaurantBrief {
        private Long id;
        private String name;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class OrderBrief {
        private Long id;
    }

    public static ReviewResponse from(Review review) {
        if (review == null) {
            return null;
        }
        return ReviewResponse.builder()
                .id(review.getId())
                .customer(briefCustomer(review))
                .restaurant(briefRestaurant(review))
                .order(review.getOrder() != null
                        ? OrderBrief.builder().id(review.getOrder().getId()).build()
                        : null)
                .rating(review.getRating())
                .comment(review.getComment())
                .foodRating(review.getFoodRating())
                .deliveryRating(review.getDeliveryRating())
                .images(review.getImages() == null ? List.of() : new java.util.ArrayList<>(review.getImages()))
                .moderationStatus(review.getModerationStatus() != null
                        ? review.getModerationStatus().name() : null)
                .ownerResponse(review.getOwnerResponse())
                .createdAt(review.getCreatedAt())
                .build();
    }

    public static List<ReviewResponse> from(List<Review> reviews) {
        if (reviews == null) {
            return List.of();
        }
        return reviews.stream().map(ReviewResponse::from).toList();
    }

    private static CustomerBrief briefCustomer(Review review) {
        if (review.getCustomer() == null) {
            return null;
        }
        // fullName may be lazily resolved; both reads stay within this DTO's
        // mapper which callers invoke inside the service's read path.
        return CustomerBrief.builder()
                .id(review.getCustomer().getId())
                .fullName(review.getCustomer().getFullName())
                .build();
    }

    private static RestaurantBrief briefRestaurant(Review review) {
        if (review.getRestaurant() == null) {
            return null;
        }
        return RestaurantBrief.builder()
                .id(review.getRestaurant().getId())
                .name(review.getRestaurant().getName())
                .build();
    }
}
