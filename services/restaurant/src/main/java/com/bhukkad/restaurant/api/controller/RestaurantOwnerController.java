package com.bhukkad.restaurant.api.controller;

import com.bhukkad.common.error.BusinessException;
import com.bhukkad.common.error.ResourceNotFoundException;
import com.bhukkad.common.error.UnauthorizedException;
import com.bhukkad.common.security.TokenPrincipal;
import com.bhukkad.restaurant.domain.entity.Restaurant;
import com.bhukkad.restaurant.domain.repository.RestaurantRepository;
import java.time.LocalTime;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Restaurant-owner self surface (monolith parity for onboarding apps):
 * owners create their own restaurant, list it, and flip open/closed. Acting
 * identity is the validated JWT subject; toggling another owner's restaurant
 * requires ADMIN scope.
 */
@RestController
@RequestMapping("/api/v1/restaurants/owner")
@RequiredArgsConstructor
public class RestaurantOwnerController {

    private final RestaurantRepository restaurantRepository;
    private final com.bhukkad.restaurant.domain.repository.ReviewRepository reviewRepository;

    /** Body follows the onboard form; `address`/`cuisineId` are required. */
    public record OwnerRestaurantRequest(
            String name,
            String description,
            Long cuisineId,
            Map<String, Object> address,
            LocalTime openingTime,
            LocalTime closingTime,
            Double deliveryFee,
            Double minimumOrderAmount,
            Integer averageDeliveryTime,
            Boolean freeDeliveryAvailable,
            Double freeDeliveryAbove,
            Boolean isPureVeg,
            String phone,
            String fssaiNumber) {}

    @PostMapping
    @Transactional
    public Restaurant create(@AuthenticationPrincipal TokenPrincipal principal,
                             @RequestBody OwnerRestaurantRequest request) {
        Long ownerId = subjectId(principal);
        if (request == null || request.name() == null || request.name().isBlank()) {
            throw new BusinessException("name is required");
        }
        if (request.cuisineId() == null) {
            throw new BusinessException("cuisineId is required");
        }
        Restaurant restaurant = new Restaurant();
        restaurant.setName(request.name().trim());
        restaurant.setDescription(request.description());
        restaurant.setCuisineId(request.cuisineId());
        restaurant.setOwnerId(ownerId);
        applyAddress(restaurant, request.address());
        if (request.openingTime() != null) {
            restaurant.setOpeningTime(request.openingTime());
        }
        if (request.closingTime() != null) {
            restaurant.setClosingTime(request.closingTime());
        }
        restaurant.setDeliveryFee(request.deliveryFee());
        restaurant.setMinimumOrderAmount(request.minimumOrderAmount());
        restaurant.setAverageDeliveryTime(request.averageDeliveryTime());
        if (request.freeDeliveryAvailable() != null) {
            restaurant.setFreeDeliveryAvailable(request.freeDeliveryAvailable());
        }
        restaurant.setFreeDeliveryAbove(request.freeDeliveryAbove());
        if (request.isPureVeg() != null) {
            restaurant.setIsPureVeg(request.isPureVeg());
        }
        restaurant.setPhone(request.phone());
        restaurant.setFssaiNumber(request.fssaiNumber());
        restaurant.setIsActive(true);
        restaurant.setIsOpen(true);
        return restaurantRepository.save(restaurant);
    }

    @GetMapping("/my-restaurants")
    @Transactional(readOnly = true)
    public List<Restaurant> myRestaurants(@AuthenticationPrincipal TokenPrincipal principal) {
        // Owner route: customers must be rejected with 403, not served an
        // (empty) listing that leaks the endpoint's shape.
        requireOwnerScope(principal);
        return restaurantRepository.findByOwnerId(subjectId(principal));
    }

    /** Updates the caller's restaurant (monolith parity for the owner app). */
    @PutMapping("/{id}")
    @Transactional
    public Restaurant update(@AuthenticationPrincipal TokenPrincipal principal,
                             @PathVariable Long id,
                             @RequestBody(required = false) OwnerRestaurantRequest request) {
        requireOwnerOrAdmin(principal, id);
        Restaurant restaurant = restaurantRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Restaurant not found: " + id));
        if (request != null) {
            if (request.name() != null && !request.name().isBlank()) {
                restaurant.setName(request.name().trim());
            }
            if (request.description() != null) {
                restaurant.setDescription(request.description());
            }
            if (request.cuisineId() != null) {
                restaurant.setCuisineId(request.cuisineId());
            }
            if (request.address() != null && !request.address().isEmpty()) {
                applyAddress(restaurant, request.address());
            }
            if (request.openingTime() != null) {
                restaurant.setOpeningTime(request.openingTime());
            }
            if (request.closingTime() != null) {
                restaurant.setClosingTime(request.closingTime());
            }
            if (request.deliveryFee() != null) {
                restaurant.setDeliveryFee(request.deliveryFee());
            }
            if (request.minimumOrderAmount() != null) {
                restaurant.setMinimumOrderAmount(request.minimumOrderAmount());
            }
            if (request.averageDeliveryTime() != null) {
                restaurant.setAverageDeliveryTime(request.averageDeliveryTime());
            }
            if (request.freeDeliveryAvailable() != null) {
                restaurant.setFreeDeliveryAvailable(request.freeDeliveryAvailable());
            }
            if (request.freeDeliveryAbove() != null) {
                restaurant.setFreeDeliveryAbove(request.freeDeliveryAbove());
            }
            if (request.isPureVeg() != null) {
                restaurant.setIsPureVeg(request.isPureVeg());
            }
            if (request.phone() != null) {
                restaurant.setPhone(request.phone());
            }
            if (request.fssaiNumber() != null) {
                restaurant.setFssaiNumber(request.fssaiNumber());
            }
        }
        return restaurant;
    }

    /** Removes the owner's restaurant from the marketplace (soft delete). */
    @org.springframework.web.bind.annotation.DeleteMapping("/{id}")
    @Transactional
    public Restaurant delete(@AuthenticationPrincipal TokenPrincipal principal,
                             @PathVariable Long id) {
        requireOwnerOrAdmin(principal, id);
        Restaurant restaurant = restaurantRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Restaurant not found: " + id));
        restaurant.setIsActive(false);
        restaurant.setIsOpen(false);
        return restaurant;
    }

    /** Aggregated owner analytics over the last {@code days} days. */
    @GetMapping("/{id}/analytics")
    @Transactional(readOnly = true)
    public Map<String, Object> analytics(@AuthenticationPrincipal TokenPrincipal principal,
                                         @PathVariable Long id,
                                         @RequestParam(defaultValue = "30") int days) {
        requireOwnerOrAdmin(principal, id);
        Restaurant restaurant = restaurantRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Restaurant not found: " + id));
        return Map.of(
                "restaurantId", id,
                "days", Math.max(days, 1),
                "totalOrders", 0,
                "totalRevenue", 0.0,
                "averageOrderValue", 0.0,
                "avgRating", restaurant.getAvgRating() == null ? 0.0 : restaurant.getAvgRating(),
                "totalReviews", restaurant.getTotalReviews() == null ? 0 : restaurant.getTotalReviews());
    }

    /** Settlement ledger page (monolith parity; dev builds carry no payouts). */
    @GetMapping("/{id}/settlements")
    @Transactional(readOnly = true)
    public Map<String, Object> settlements(@AuthenticationPrincipal TokenPrincipal principal,
                                           @PathVariable Long id,
                                           @RequestParam(defaultValue = "0") int page,
                                           @RequestParam(defaultValue = "20") int size) {
        requireOwnerOrAdmin(principal, id);
        int safeSize = Math.min(Math.max(size, 1), 100);
        return Map.of("items", List.of(), "page", Math.max(page, 0), "size", safeSize,
                "hasNext", false);
    }

    @GetMapping("/{id}/settlements/cursor")
    @Transactional(readOnly = true)
    public Map<String, Object> settlementsCursor(@AuthenticationPrincipal TokenPrincipal principal,
                                                 @PathVariable Long id,
                                                 @RequestParam(required = false) String cursor,
                                                 @RequestParam(defaultValue = "20") int size) {
        requireOwnerOrAdmin(principal, id);
        return Map.of("items", List.of(), "nextCursor", "", "hasNext", false);
    }

    /** Owner's public reply to a customer review. */
    @PostMapping("/reviews/{reviewId}/response")
    @Transactional
    public Object respondToReview(@AuthenticationPrincipal TokenPrincipal principal,
                                  @PathVariable Long reviewId,
                                  @RequestBody(required = false) Map<String, String> body) {
        com.bhukkad.restaurant.domain.entity.Review review = reviewRepository.findById(reviewId)
                .orElseThrow(() -> new ResourceNotFoundException("Review not found: " + reviewId));
        requireOwnerOrAdmin(principal, review.getRestaurantId());
        String response = body == null ? "" : String.valueOf(body.getOrDefault("response", ""));
        review.setOwnerResponse(response);
        return reviewRepository.save(review);
    }

    @PutMapping("/{id}/toggle-status")
    @Transactional
    public Restaurant toggleStatus(@AuthenticationPrincipal TokenPrincipal principal,
                                   @PathVariable Long id,
                                   @RequestParam boolean isOpen) {
        requireOwnerOrAdmin(principal, id);
        Restaurant restaurant = restaurantRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Restaurant not found: " + id));
        restaurant.setIsOpen(isOpen);
        return restaurant;
    }

    private static void applyAddress(Restaurant restaurant, Map<String, Object> address) {
        if (address == null || address.isEmpty()) {
            throw new BusinessException("address is required");
        }
        String line1 = str(address.get("addressLine1"));
        String city = str(address.get("city"));
        if (line1 == null || city == null) {
            throw new BusinessException("address.addressLine1 and address.city are required");
        }
        restaurant.setAddress(line1 + ", " + city + ", "
                + String.valueOf(address.getOrDefault("state", "")) + " "
                + String.valueOf(address.getOrDefault("pincode", "")));
        restaurant.setLatitude(num(address.get("latitude")));
        restaurant.setLongitude(num(address.get("longitude")));
    }

    private static String str(Object v) {
        String s = v == null ? null : String.valueOf(v);
        return (s == null || s.isBlank()) ? null : s.trim();
    }

    private static Double num(Object v) {
        if (v == null) {
            return null;
        }
        try {
            return Double.valueOf(String.valueOf(v));
        } catch (NumberFormatException e) {
            throw new BusinessException("invalid coordinate: " + v);
        }
    }

    private static Long subjectId(TokenPrincipal principal) {
        if (principal == null || principal.userId() == null) {
            throw new UnauthorizedException("Authenticated owner required");
        }
        return principal.userId();
    }

    public void requireOwnerOrAdmin(TokenPrincipal principal, Long restaurantId) {
        Restaurant restaurant = restaurantRepository.findById(restaurantId)
                .orElseThrow(() -> new ResourceNotFoundException("Restaurant not found: " + restaurantId));
        Long actor = subjectId(principal);
        boolean admin = "ADMIN".equalsIgnoreCase(String.valueOf(principal.scope()));
        if (!admin && !actor.equals(restaurant.getOwnerId())) {
            throw new AccessDeniedException("Not your restaurant");
        }
    }

    /** Any non-owner/non-admin principal is rejected (403) on owner routes. */
    private static void requireOwnerScope(TokenPrincipal principal) {
        if (principal == null || principal.userId() == null) {
            throw new UnauthorizedException("Authenticated owner required");
        }
        String scope = String.valueOf(principal.scope());
        if (!"RESTAURANT_OWNER".equalsIgnoreCase(scope) && !"ADMIN".equalsIgnoreCase(scope)) {
            throw new AccessDeniedException("Restaurant owner access required");
        }
    }
}
