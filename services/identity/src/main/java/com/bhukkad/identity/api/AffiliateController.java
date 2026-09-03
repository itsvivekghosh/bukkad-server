package com.bhukkad.identity.api;

import com.bhukkad.identity.dto.request.AffiliateCodeRequest;
import com.bhukkad.identity.dto.response.AffiliateCodeResponse;
import com.bhukkad.identity.dto.response.AffiliateStatsResponse;
import com.bhukkad.identity.service.AffiliateService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
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
    public List<AffiliateCodeResponse> listAll() {
        return affiliateService.listAll();
    }

    @PostMapping("/codes")
    public AffiliateCodeResponse create(@Valid @RequestBody AffiliateCodeRequest request) {
        return affiliateService.create(request);
    }

    @PutMapping("/codes/{id}")
    public AffiliateCodeResponse update(@PathVariable Long id, @Valid @RequestBody AffiliateCodeRequest request) {
        return affiliateService.update(id, request);
    }

    @DeleteMapping("/codes/{id}")
    public void deactivate(@PathVariable Long id) {
        affiliateService.deactivate(id);
    }

    @GetMapping("/codes/{id}/stats")
    public AffiliateStatsResponse stats(@PathVariable Long id) {
        return affiliateService.getStats(id);
    }

    @PostMapping("/track")
    public void track(@RequestParam String code, @RequestParam Long customerId) {
        affiliateService.recordSignup(code, customerId);
    }
}
