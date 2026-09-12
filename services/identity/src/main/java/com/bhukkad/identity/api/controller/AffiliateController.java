package com.bhukkad.identity.api.controller;

import com.bhukkad.identity.api.dto.request.AffiliateCodeRequest;
import com.bhukkad.identity.api.dto.response.AffiliateCodeResponse;
import com.bhukkad.identity.domain.service.impl.AffiliateService;

import com.bhukkad.common.security.PrincipalGuard;
import com.bhukkad.common.security.TokenPrincipal;
import com.bhukkad.identity.api.dto.request.AffiliateCodeRequest;
import com.bhukkad.identity.api.dto.response.AffiliateCodeResponse;
import com.bhukkad.identity.api.dto.response.AffiliateStatsResponse;
import com.bhukkad.identity.domain.service.impl.AffiliateService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * Affiliate admin surface (port of the monolith
 * {@code AffiliateController} admin endpoints, WAVE 2) plus the public
 * click-tracking endpoint from the slim WAVE 1 controller.
 */
@RestController
@RequestMapping("/api/v1/affiliate")
@RequiredArgsConstructor
public class AffiliateController {

    private final AffiliateService affiliateService;

    @GetMapping("/codes")
    @PreAuthorize("hasRole('ADMIN')")
    public List<AffiliateCodeResponse> listAll() {
        return affiliateService.listAll();
    }

    @PostMapping("/codes")
    @PreAuthorize("hasRole('ADMIN')")
    public AffiliateCodeResponse create(@Valid @RequestBody AffiliateCodeRequest request) {
        return affiliateService.create(request);
    }

    @PutMapping("/codes/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    public AffiliateCodeResponse update(@PathVariable Long id, @Valid @RequestBody AffiliateCodeRequest request) {
        return affiliateService.update(id, request);
    }

    @DeleteMapping("/codes/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    public void deactivate(@PathVariable Long id) {
        affiliateService.deactivate(id);
    }

    @GetMapping("/codes/{id}/stats")
    @PreAuthorize("hasRole('ADMIN')")
    public AffiliateStatsResponse stats(@PathVariable Long id) {
        return affiliateService.getStats(id);
    }

    /**
     * Attributes a signup to a referral/affiliate code. The customer id is
     * bound to the authenticated principal — callers cannot fabricate
     * attribution for arbitrary customer ids.
     */
    @PostMapping("/track")
    public void track(@AuthenticationPrincipal TokenPrincipal principal,
                      @RequestParam String code,
                      @RequestParam Long customerId) {
        PrincipalGuard.requireSelfOrAdmin(principal, customerId);
        affiliateService.recordSignup(code, customerId);
    }
}
