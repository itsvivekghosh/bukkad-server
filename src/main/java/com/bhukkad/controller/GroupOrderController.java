package com.bhukkad.controller;

import com.bhukkad.config.ApiPaths;
import com.bhukkad.dto.request.GroupOrderCreateRequest;
import com.bhukkad.dto.request.GroupOrderInviteRequest;
import com.bhukkad.dto.request.GroupOrderSplitRequest;
import com.bhukkad.dto.response.ApiResponse;
import com.bhukkad.dto.response.GroupOrderResponse;
import com.bhukkad.order.GroupOrderService;
import com.bhukkad.security.SecurityUtils;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping(ApiPaths.V1_PREFIX + "/customers/group-orders")
@RequiredArgsConstructor
@PreAuthorize("hasRole('CUSTOMER')")
@Tag(name = "Group Orders", description = "REST endpoints for group ordering and bill splitting")
public class GroupOrderController {

    private final GroupOrderService groupOrderService;
    private final SecurityUtils securityUtils;

    @PostMapping
    @Operation(summary = "Create group order")
    public ResponseEntity<ApiResponse<GroupOrderResponse>> createGroupOrder(
            @Valid @RequestBody GroupOrderCreateRequest request) {
        GroupOrderResponse response = groupOrderService.createGroupOrder(
                securityUtils.getCurrentUserId(), request.getTitle());
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.success("Group order created", response));
    }

    @PostMapping("/{id}/invite")
    @Operation(summary = "Invite member by phone")
    public ResponseEntity<ApiResponse<GroupOrderResponse>> inviteMember(
            @PathVariable Long id,
            @Valid @RequestBody GroupOrderInviteRequest request) {
        GroupOrderResponse response = groupOrderService.inviteMember(
                id, securityUtils.getCurrentUserId(), request.getPhone());
        return ResponseEntity.ok(ApiResponse.success("Member invited", response));
    }

    @PostMapping("/{id}/join")
    @Operation(summary = "Join group order")
    public ResponseEntity<ApiResponse<GroupOrderResponse>> joinGroup(@PathVariable Long id) {
        GroupOrderResponse response = groupOrderService.joinGroup(
                id, securityUtils.getCurrentUserId());
        return ResponseEntity.ok(ApiResponse.success("Joined group", response));
    }

    @GetMapping("/{id}")
    @Operation(summary = "Get group order detail")
    public ResponseEntity<ApiResponse<GroupOrderResponse>> getGroup(@PathVariable Long id) {
        GroupOrderResponse response = groupOrderService.getGroup(
                id, securityUtils.getCurrentUserId());
        return ResponseEntity.ok(ApiResponse.success(response));
    }

    @PostMapping("/{id}/split")
    @Operation(summary = "Record bill split")
    public ResponseEntity<ApiResponse<GroupOrderResponse>> splitPayment(
            @PathVariable Long id,
            @Valid @RequestBody GroupOrderSplitRequest request) {
        GroupOrderResponse response = groupOrderService.splitPayment(
                id, securityUtils.getCurrentUserId(), request.getShares());
        return ResponseEntity.ok(ApiResponse.success("Split recorded", response));
    }

    @PostMapping("/{id}/place")
    @Operation(summary = "Place group order")
    public ResponseEntity<ApiResponse<GroupOrderResponse>> placeGroupOrder(@PathVariable Long id) {
        GroupOrderResponse response = groupOrderService.placeGroupOrder(
                id, securityUtils.getCurrentUserId());
        return ResponseEntity.ok(ApiResponse.success("Group order placed", response));
    }
}