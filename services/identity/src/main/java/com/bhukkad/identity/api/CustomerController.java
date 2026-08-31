package com.bhukkad.identity.api;

import com.bhukkad.identity.domain.FavoriteRestaurant;
import com.bhukkad.identity.service.AccountProfileService;
import com.bhukkad.identity.service.ConsentService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * Customer profile + favorites + consent surface (port of monolith
 * {@code CustomerController}).
 */
@RestController
@RequestMapping("/api/v1/customers/{customerId}")
@RequiredArgsConstructor
public class CustomerController {

    private final AccountProfileService profileService;
    private final ConsentService consentService;

    public record ConsentRequest(String purpose, boolean granted) {}

    @PostMapping("/favorites")
    public FavoriteRestaurant addFavorite(@PathVariable Long customerId, @RequestParam Long restaurantId) {
        return profileService.addFavorite(customerId, restaurantId);
    }

    @DeleteMapping("/favorites/{restaurantId}")
    public void removeFavorite(@PathVariable Long customerId, @PathVariable Long restaurantId) {
        profileService.removeFavorite(customerId, restaurantId);
    }

    @GetMapping("/favorites")
    public List<FavoriteRestaurant> favorites(@PathVariable Long customerId) {
        return profileService.favorites(customerId);
    }

    @PostMapping("/consent")
    public Object recordConsent(@PathVariable Long customerId, @RequestBody ConsentRequest request) {
        return consentService.record(customerId, request.purpose(), request.granted());
    }

    @GetMapping("/consent/{purpose}")
    public boolean hasConsent(@PathVariable Long customerId, @PathVariable String purpose) {
        return consentService.hasConsent(customerId, purpose);
    }
}