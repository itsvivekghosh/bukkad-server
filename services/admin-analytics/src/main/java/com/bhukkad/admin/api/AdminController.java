package com.bhukkad.admin.api;

import com.bhukkad.admin.domain.AuditEvent;
import com.bhukkad.admin.domain.FraudEvent;
import com.bhukkad.admin.domain.RestaurantOrderStat;
import com.bhukkad.admin.service.AdminQueryService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import org.springframework.security.access.prepost.PreAuthorize;

@RestController
@RequestMapping("/api/v1/admin")
@RequiredArgsConstructor
@PreAuthorize("hasRole('ADMIN')")
public class AdminController {

    private final AdminQueryService queryService;

    @GetMapping("/audit")
    public List<AuditEvent> audit(@RequestParam String entityType, @RequestParam Long entityId) {
        return queryService.auditTrail(entityType, entityId);
    }

    @GetMapping("/fraud")
    public List<FraudEvent> fraud(@RequestParam(required = false) String status) {
        return queryService.fraudAlerts(status);
    }

    @PostMapping("/fraud")
    public FraudEvent flagFraud(@RequestParam Long customerId, @RequestParam String rule,
                                 @RequestParam(defaultValue = "HIGH") String severity) {
        return queryService.flagFraud(customerId, rule, severity);
    }

    @GetMapping("/restaurants/stats")
    public List<RestaurantOrderStat> restaurantStats() {
        return queryService.restaurantStats();
    }
}