package com.bhukkad.identity.api.controller;

import com.bhukkad.identity.domain.entity.Customer;
import com.bhukkad.identity.domain.entity.FavoriteRestaurant;
import com.bhukkad.identity.domain.service.impl.AccountProfileService;
import com.bhukkad.identity.domain.service.impl.ConsentService;

import com.bhukkad.common.security.PrincipalGuard;
import com.bhukkad.common.security.TokenPrincipal;
import com.bhukkad.identity.domain.entity.FavoriteRestaurant;
import com.bhukkad.identity.domain.service.impl.AccountProfileService;
import com.bhukkad.identity.domain.service.impl.ConsentService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * Customer profile + favorites + consent surface (port of monolith
 * {@code CustomerController}).
 *
 * <p>Every endpoint enforces subject-or-admin ownership: favorites and
 * consent records are per-customer PII / legally significant records and must
 * not be readable or writable across customers.</p>
 */
@RestController
@RequestMapping("/api/v1/customers/{customerId}")
@RequiredArgsConstructor
public class CustomerController {

    private final AccountProfileService profileService;
    private final ConsentService consentService;

    public record ConsentRequest(String purpose, boolean granted) {}

    @PostMapping("/favorites")
    public FavoriteRestaurant addFavorite(@AuthenticationPrincipal TokenPrincipal principal,
                                          @PathVariable Long customerId,
                                          @RequestParam Long restaurantId) {
        PrincipalGuard.requireSelfOrAdmin(principal, customerId);
        return profileService.addFavorite(customerId, restaurantId);
    }

    @DeleteMapping("/favorites/{restaurantId}")
    public void removeFavorite(@AuthenticationPrincipal TokenPrincipal principal,
                               @PathVariable Long customerId,
                               @PathVariable Long restaurantId) {
        PrincipalGuard.requireSelfOrAdmin(principal, customerId);
        profileService.removeFavorite(customerId, restaurantId);
    }

    @GetMapping("/favorites")
    public List<FavoriteRestaurant> favorites(@AuthenticationPrincipal TokenPrincipal principal,
                                              @PathVariable Long customerId) {
        PrincipalGuard.requireSelfOrAdmin(principal, customerId);
        return profileService.favorites(customerId);
    }

    @PostMapping("/consent")
    public Object recordConsent(@AuthenticationPrincipal TokenPrincipal principal,
                                @PathVariable Long customerId,
                                @RequestBody ConsentRequest request) {
        PrincipalGuard.requireSelfOrAdmin(principal, customerId);
        return consentService.record(customerId, request.purpose(), request.granted());
    }

    @GetMapping("/consent/{purpose}")
    public boolean hasConsent(@AuthenticationPrincipal TokenPrincipal principal,
                              @PathVariable Long customerId,
                              @PathVariable String purpose) {
        PrincipalGuard.requireSelfOrAdmin(principal, customerId);
        return consentService.hasConsent(customerId, purpose);
    }
}
