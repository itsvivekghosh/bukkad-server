package com.bhukkad.controller;

import com.bhukkad.config.ApiPaths;
import com.bhukkad.delivery.OrderEtaHistoryService;
import com.bhukkad.dto.response.ApiResponse;
import com.bhukkad.dto.response.OrderEtaDetailResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

/**
 * V14 delivery truth endpoints: smarter ETA with confidence bands and history.
 */
@RestController
@RequestMapping(ApiPaths.V1_PREFIX + "/delivery-truth")
@RequiredArgsConstructor
public class DeliveryTruthController {

    private final OrderEtaHistoryService orderEtaHistoryService;

    /** Detailed ETA breakdown with confidence band and snapshot history. */
    @GetMapping("/orders/{orderId}/eta")
    @PreAuthorize("hasAnyRole('CUSTOMER','RESTAURANT_OWNER','DELIVERY_AGENT','ADMIN')")
    public ResponseEntity<ApiResponse<OrderEtaDetailResponse>> getOrderEtaDetail(@PathVariable Long orderId) {
        return ResponseEntity.ok(ApiResponse.success(orderEtaHistoryService.getEtaDetail(orderId)));
    }
}
