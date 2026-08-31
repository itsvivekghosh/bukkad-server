package com.bhukkad.identity.api;

import com.bhukkad.identity.domain.AffiliateCode;
import com.bhukkad.identity.domain.AffiliateReferral;
import com.bhukkad.identity.service.AffiliateService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;

/**
 * Affiliate + referral endpoints (port of monolith {@code AffiliateController}
 * + {@code ReferralController}).
 */
@RestController
@RequestMapping("/api/v1/affiliate")
@RequiredArgsConstructor
public class AffiliateController {

    private final AffiliateService affiliateService;

    @PostMapping("/codes")
    public AffiliateCode createCode(@RequestParam Long restaurantId, @RequestParam String code,
                                    @RequestParam(required = false) BigDecimal commissionPct) {
        return affiliateService.createCode(restaurantId, code, commissionPct);
    }

    @PostMapping("/track")
    public AffiliateReferral track(@RequestParam String code, @RequestParam Long referredBy) {
        return affiliateService.trackClick(code, referredBy);
    }
}