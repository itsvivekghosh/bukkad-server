package com.bhukkad.order.api;

import com.bhukkad.order.domain.GroupOrder;
import com.bhukkad.order.domain.GroupOrderMember;
import com.bhukkad.order.service.SocialOrderService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * Group-order endpoints (port of monolith's {@code GroupOrderController}).
 */
@RestController
@RequestMapping("/api/v1/customers/{customerId}/group-orders")
@RequiredArgsConstructor
public class GroupOrderController {

    private final SocialOrderService socialService;

    @PostMapping
    public GroupOrder create(@PathVariable Long customerId, @RequestParam Long restaurantId) {
        return socialService.createGroupOrder(customerId, restaurantId);
    }

    @PostMapping("/{groupOrderId}/join")
    public GroupOrderMember join(@PathVariable Long groupOrderId, @PathVariable Long customerId) {
        return socialService.join(groupOrderId, customerId);
    }

    @GetMapping("/{groupOrderId}/members")
    public List<GroupOrderMember> members(@PathVariable Long groupOrderId) {
        return socialService.members(groupOrderId);
    }
}