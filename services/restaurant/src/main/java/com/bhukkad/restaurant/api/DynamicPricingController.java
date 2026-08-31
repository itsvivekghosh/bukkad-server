package com.bhukkad.restaurant.api;

import com.bhukkad.restaurant.domain.DynamicPricingRule;
import com.bhukkad.restaurant.service.DynamicPricingService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.time.LocalTime;
import java.util.List;

@RestController
@RequestMapping("/api/v1/pricing")
@RequiredArgsConstructor
public class DynamicPricingController {

    private final DynamicPricingService pricingService;

    @PostMapping
    public DynamicPricingRule create(@RequestParam Long restaurantId, @RequestParam String name,
                                     @RequestParam BigDecimal multiplier,
                                     @RequestParam(required = false) LocalTime startTime,
                                     @RequestParam(required = false) LocalTime endTime) {
        return pricingService.create(restaurantId, name, multiplier, startTime, endTime);
    }

    @GetMapping
    public List<DynamicPricingRule> active(@RequestParam Long restaurantId) {
        return pricingService.active(restaurantId);
    }

    @DeleteMapping("/{ruleId}")
    public void deactivate(@PathVariable Long ruleId) {
        pricingService.deactivate(ruleId);
    }
}