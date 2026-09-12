package com.bhukkad.order.api.controller;

import com.bhukkad.common.error.BusinessException;
import com.bhukkad.common.error.ResourceNotFoundException;
import com.bhukkad.common.security.PrincipalGuard;
import com.bhukkad.common.security.TokenPrincipal;
import com.bhukkad.order.domain.entity.GroupOrder;
import com.bhukkad.order.domain.entity.GroupOrderMember;
import com.bhukkad.order.domain.service.impl.SocialOrderService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * Group-order endpoints (port of monolith's {@code GroupOrderController}).
 *
 * <p>Joins require an existing OPEN group; membership listings are host/admin
 * reads (member rows carry partial phone PII in {@code invitePhone}).</p>
 */
@RestController
@RequestMapping("/api/v1/customers/{customerId}/group-orders")
@RequiredArgsConstructor
public class GroupOrderController {

    private final SocialOrderService socialService;

    @PostMapping
    public GroupOrder create(@AuthenticationPrincipal TokenPrincipal principal,
                             @PathVariable Long customerId,
                             @RequestParam Long restaurantId) {
        PrincipalGuard.requireSelfOrAdmin(principal, customerId);
        return socialService.createGroupOrder(customerId, restaurantId);
    }

    @PostMapping("/{groupOrderId}/join")
    public GroupOrderMember join(@AuthenticationPrincipal TokenPrincipal principal,
                                 @PathVariable Long groupOrderId,
                                 @PathVariable Long customerId) {
        // The joining member is the authenticated caller; path/customerId
        // mismatch or cross-customer joins are rejected.
        PrincipalGuard.requireSelfOrAdmin(principal, customerId);
        GroupOrder group = socialService.groupOrder(groupOrderId);
        if (!GroupOrder.STATUS_OPEN.equals(group.getStatus())) {
            throw new BusinessException("Group order is not open for joining");
        }
        return socialService.join(groupOrderId, customerId);
    }

    @GetMapping("/{groupOrderId}/members")
    public List<GroupOrderMember> members(@AuthenticationPrincipal TokenPrincipal principal,
                                          @PathVariable Long groupOrderId,
                                          @PathVariable Long customerId) {
        // Only the group host (or an admin) may enumerate members.
        PrincipalGuard.requireSelfOrAdmin(principal, customerId);
        GroupOrder group = socialService.groupOrder(groupOrderId);
        if (!customerId.equals(group.getHostUserId())) {
            throw new org.springframework.security.access.AccessDeniedException(
                    "Only the group host can list members");
        }
        return socialService.members(groupOrderId);
    }
}
