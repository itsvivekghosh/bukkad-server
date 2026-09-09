package com.bhukkad.growth.api;

import com.bhukkad.common.error.UnauthorizedException;
import com.bhukkad.common.security.TokenPrincipal;
import com.bhukkad.growth.dto.LoyaltyPointsResponse;
import com.bhukkad.growth.service.LoyaltyService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Monolith-parity self surface for the customer app's loyalty card: the
 * acting customer is always the JWT subject (no path id), so cross-customer
 * reads are impossible by construction.
 */
@RestController
@RequiredArgsConstructor
public class LoyaltySelfController {

    private final LoyaltyService loyaltyService;

    @GetMapping("/api/v1/customers/loyalty-points")
    public ResponseEntity<LoyaltyPointsResponse> loyaltyPoints(
            @AuthenticationPrincipal TokenPrincipal principal) {
        if (principal == null || principal.userId() == null) {
            throw new UnauthorizedException("Authenticated customer required");
        }
        return ResponseEntity.ok(loyaltyService.getLoyaltyPoints(principal.userId()));
    }
}
