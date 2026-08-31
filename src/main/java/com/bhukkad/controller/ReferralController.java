package com.bhukkad.controller;

import com.bhukkad.config.ApiPaths;
import com.bhukkad.dto.request.ReferralCodeValidationRequest;
import com.bhukkad.dto.response.ApiResponse;
import com.bhukkad.dto.response.ReferralInfoResponse;
import com.bhukkad.referral.ReferralService;
import com.bhukkad.security.SecurityUtils;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping(ApiPaths.V1_PREFIX + "/referrals")
@PreAuthorize("isAuthenticated()")
@RequiredArgsConstructor
@Tag(name = "Referral", description = "REST endpoints for Referral System")
public class ReferralController {

    private final ReferralService referralService;
    private final SecurityUtils securityUtils;

    /**
     * Generates (and persists) a referral code for the current user.
     *
     * <p>If the user already has a referral code, the existing code is
     * returned unchanged.</p>
     */
    @Operation(summary = "Generate referral code for current user")
    @PostMapping("/generate")
    public ResponseEntity<ApiResponse<String>> generateReferralCode() {
        Long userId = securityUtils.getCurrentUserId();
        referralService.assertNotRateLimited("referral:generate:" + userId);
        String referralCode = referralService.generateAndSaveReferralCode(userId);
        return ResponseEntity.ok(ApiResponse.success(referralCode));
    }

    /**
     * Validates whether a referral code belongs to an active customer.
     */
    @Operation(summary = "Validate a referral code")
    @PostMapping("/validate")
    public ResponseEntity<ApiResponse<Boolean>> validateReferralCode(@Valid @RequestBody ReferralCodeValidationRequest request) {
        Long userId = securityUtils.getCurrentUserId();
        referralService.assertNotRateLimited("referral:validate:" + userId);
        boolean isValid = referralService.isValidReferralCode(request.getReferralCode());
        return ResponseEntity.ok(ApiResponse.success(isValid));
    }

    /**
     * Returns referral rewards information (code, referral count, bonus earned)
     * for the current user.
     */
    @Operation(summary = "Get referral rewards information for current user")
    @GetMapping("/rewards")
    public ResponseEntity<ApiResponse<ReferralInfoResponse>> getReferralRewards() {
        Long userId = securityUtils.getCurrentUserId();
        ReferralInfoResponse info = referralService.getReferralInfo(userId);
        return ResponseEntity.ok(ApiResponse.success(info));
    }
}