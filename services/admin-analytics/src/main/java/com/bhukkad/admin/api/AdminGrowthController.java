package com.bhukkad.admin.api;

import com.bhukkad.admin.domain.RestaurantOrderStat;
import com.bhukkad.admin.domain.RestaurantOrderStatRepository;
import com.bhukkad.admin.service.AdminQueryService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import org.springframework.security.access.prepost.PreAuthorize;

/**
 * Growth/admin dashboards (port of monolith {@code AdminGrowthController}).
 */
@RestController
@RequestMapping("/api/v1/admin/growth")
@RequiredArgsConstructor
@PreAuthorize("hasRole('ADMIN')")
public class AdminGrowthController {

    private final RestaurantOrderStatRepository statRepository;
    private final AdminQueryService queryService;

    @GetMapping("/restaurants")
    public List<RestaurantOrderStat> restaurantStats() {
        return statRepository.findAll();
    }

    @GetMapping("/restaurants/top")
    public List<RestaurantOrderStat> topRestaurants(@RequestParam(defaultValue = "10") int limit) {
        return statRepository.findAll().stream()
                .sorted((a, b) -> Long.compare(b.getOrderCount(), a.getOrderCount()))
                .limit(limit)
                .toList();
    }

    @GetMapping("/fraud/summary")
    public Object fraudSummary(@RequestParam(required = false) String status) {
        return queryService.fraudAlerts(status);
    }
}