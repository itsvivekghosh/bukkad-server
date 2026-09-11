package com.bhukkad.admin.api;

import com.bhukkad.admin.domain.RestaurantOrderStat;
import com.bhukkad.admin.domain.RestaurantOrderStatRepository;
import com.bhukkad.admin.service.AdminQueryService;
import com.bhukkad.common.scan.AllowFullScan;
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
        // PERF-3: same bounded page as the canonical query service (was a
        // direct whole-table findAll here).
        return queryService.restaurantStats();
    }

    /**
     * PERF-3: top-N now runs ORDER BY order_count DESC + LIMIT in SQL (was
     * findAll + JVM sort + limit on servlet threads). Request shape unchanged;
     * the cap doubles as the page size, bounded by {@value AdminQueryService#LIST_PAGE_CAP}.
     */
    @GetMapping("/restaurants/top")
    @AllowFullScan(reason = "G-6 reviewed: SQL-side ORDER BY + LIMIT page of LIST_PAGE_CAP rows — not a whole-table read")
    public List<RestaurantOrderStat> topRestaurants(@RequestParam(defaultValue = "10") int limit) {
        int capped = Math.max(1, Math.min(limit, AdminQueryService.LIST_PAGE_CAP));
        return statRepository.findAll(org.springframework.data.domain.PageRequest.of(
                        0, capped,
                        org.springframework.data.domain.Sort.by(
                                org.springframework.data.domain.Sort.Direction.DESC, "orderCount")))
                .getContent();
    }

    @GetMapping("/fraud/summary")
    public Object fraudSummary(@RequestParam(required = false) String status) {
        return queryService.fraudAlerts(status);
    }
}