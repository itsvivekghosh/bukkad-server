package com.bhukkad.restaurant.api;

import com.bhukkad.restaurant.domain.Restaurant;
import com.bhukkad.restaurant.domain.RestaurantRepository;
import com.bhukkad.restaurant.domain.PromoBanner;
import com.bhukkad.restaurant.domain.PromoBannerRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Home/mobile feed (port of monolith {@code HomeFeedController} +
 * {@code MobileFeedController}): active restaurants + active promo banners.
 */
@RestController
@RequestMapping("/api/v1/feed")
@RequiredArgsConstructor
public class FeedController {

    private final RestaurantRepository restaurantRepository;
    private final PromoBannerRepository bannerRepository;

    @GetMapping("/home")
    public Feed feed() {
        List<Restaurant> restaurants = restaurantRepository.findAll().stream()
                .filter(r -> Boolean.TRUE.equals(r.getIsActive()))
                .toList();
        return new Feed(restaurants, bannerRepository.findByActiveTrue());
    }

    public record Feed(List<Restaurant> restaurants, List<PromoBanner> banners) {
    }
}