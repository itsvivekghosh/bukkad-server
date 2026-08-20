package com.bhukkad.controller;

import com.bhukkad.admin.AdminOperationsDashboardService;
import com.bhukkad.config.ApiPaths;
import com.bhukkad.dto.request.CityConfigRequest;
import com.bhukkad.dto.request.DeliveryZoneRequest;
import com.bhukkad.dto.request.PromoBannerRequest;
import com.bhukkad.dto.request.PromotionCampaignRequest;
import com.bhukkad.dto.response.AdminOperationsDashboardResponse;
import com.bhukkad.dto.response.ApiResponse;
import com.bhukkad.dto.response.CityConfigResponse;
import com.bhukkad.dto.response.DeliveryZoneResponse;
import com.bhukkad.dto.response.PromoBannerResponse;
import com.bhukkad.dto.response.PromotionCampaignResponse;
import com.bhukkad.entity.SettlementRun;
import com.bhukkad.feed.PromoBannerAdminService;
import com.bhukkad.promotion.PromotionAdminService;
import com.bhukkad.settlement.SettlementAutomationScheduler;
import com.bhukkad.zone.CityConfigService;
import com.bhukkad.zone.DeliveryZoneAdminService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * Admin endpoints for V14–V16: zones, city configs, promotions, settlement automation, ops dashboard.
 */
@RestController
@RequestMapping(ApiPaths.V1_PREFIX + "/admin")
@RequiredArgsConstructor
@PreAuthorize("hasRole('ADMIN')")
public class AdminScaleController {

    private final DeliveryZoneAdminService deliveryZoneAdminService;
    private final PromotionAdminService promotionAdminService;
    private final PromoBannerAdminService promoBannerAdminService;
    private final SettlementAutomationScheduler settlementAutomationScheduler;
    private final AdminOperationsDashboardService adminOperationsDashboardService;
    private final CityConfigService cityConfigService;

    // ── V14: Delivery zones ──────────────────────────────────────────────────

    @GetMapping("/zones")
    public ResponseEntity<ApiResponse<List<DeliveryZoneResponse>>> listZones() {
        return ResponseEntity.ok(ApiResponse.success(deliveryZoneAdminService.listAll()));
    }

    @PostMapping("/zones")
    public ResponseEntity<ApiResponse<DeliveryZoneResponse>> createZone(@RequestBody DeliveryZoneRequest request) {
        return ResponseEntity.ok(ApiResponse.success("Zone created", deliveryZoneAdminService.create(request)));
    }

    @PutMapping("/zones/{zoneId}")
    public ResponseEntity<ApiResponse<DeliveryZoneResponse>> updateZone(
            @PathVariable Long zoneId, @RequestBody DeliveryZoneRequest request) {
        return ResponseEntity.ok(ApiResponse.success("Zone updated", deliveryZoneAdminService.update(zoneId, request)));
    }

    @DeleteMapping("/zones/{zoneId}")
    public ResponseEntity<ApiResponse<Void>> deleteZone(@PathVariable Long zoneId) {
        deliveryZoneAdminService.delete(zoneId);
        return ResponseEntity.ok(ApiResponse.success("Zone deleted", null));
    }

    // ── Multi-city/Region Support: per-city config ─────────────────────────

    @GetMapping("/cities")
    public ResponseEntity<ApiResponse<List<CityConfigResponse>>> listCities() {
        return ResponseEntity.ok(ApiResponse.success(cityConfigService.listAll()));
    }

    @PostMapping("/cities")
    public ResponseEntity<ApiResponse<CityConfigResponse>> createCity(@Valid @RequestBody CityConfigRequest request) {
        return ResponseEntity.ok(ApiResponse.success("City config created", cityConfigService.create(request)));
    }

    @PutMapping("/cities/{cityId}")
    public ResponseEntity<ApiResponse<CityConfigResponse>> updateCity(
            @PathVariable Long cityId, @Valid @RequestBody CityConfigRequest request) {
        return ResponseEntity.ok(ApiResponse.success("City config updated", cityConfigService.update(cityId, request)));
    }

    @DeleteMapping("/cities/{cityId}")
    public ResponseEntity<ApiResponse<Void>> deleteCity(@PathVariable Long cityId) {
        cityConfigService.delete(cityId);
        return ResponseEntity.ok(ApiResponse.success("City config deleted", null));
    }

    // ── V15: Promotions engine ─────────────────────────────────────────────

    @GetMapping("/promotions/campaigns")
    public ResponseEntity<ApiResponse<List<PromotionCampaignResponse>>> listCampaigns() {
        return ResponseEntity.ok(ApiResponse.success(promotionAdminService.listAll()));
    }

    @PostMapping("/promotions/campaigns")
    public ResponseEntity<ApiResponse<PromotionCampaignResponse>> createCampaign(
            @RequestBody PromotionCampaignRequest request) {
        return ResponseEntity.ok(ApiResponse.success("Campaign created", promotionAdminService.create(request)));
    }

    @PutMapping("/promotions/campaigns/{campaignId}")
    public ResponseEntity<ApiResponse<PromotionCampaignResponse>> updateCampaign(
            @PathVariable Long campaignId, @RequestBody PromotionCampaignRequest request) {
        return ResponseEntity.ok(ApiResponse.success("Campaign updated",
                promotionAdminService.update(campaignId, request)));
    }

    @DeleteMapping("/promotions/campaigns/{campaignId}")
    public ResponseEntity<ApiResponse<Void>> deactivateCampaign(@PathVariable Long campaignId) {
        promotionAdminService.deactivate(campaignId);
        return ResponseEntity.ok(ApiResponse.success("Campaign deactivated", null));
    }

    @GetMapping("/promotions/banners")
    public ResponseEntity<ApiResponse<List<PromoBannerResponse>>> listBanners() {
        return ResponseEntity.ok(ApiResponse.success(promoBannerAdminService.listAll()));
    }

    @PostMapping("/promotions/banners")
    public ResponseEntity<ApiResponse<PromoBannerResponse>> createBanner(@RequestBody PromoBannerRequest request) {
        return ResponseEntity.ok(ApiResponse.success("Banner created", promoBannerAdminService.create(request)));
    }

    @PutMapping("/promotions/banners/{bannerId}")
    public ResponseEntity<ApiResponse<PromoBannerResponse>> updateBanner(
            @PathVariable Long bannerId, @RequestBody PromoBannerRequest request) {
        return ResponseEntity.ok(ApiResponse.success("Banner updated", promoBannerAdminService.update(bannerId, request)));
    }

    // ── V16: Settlement automation & ops dashboard ─────────────────────────

    @GetMapping("/operations-dashboard")
    public ResponseEntity<ApiResponse<AdminOperationsDashboardResponse>> getOperationsDashboard() {
        return ResponseEntity.ok(ApiResponse.success(adminOperationsDashboardService.getDashboard()));
    }

    @PostMapping("/settlements/run")
    public ResponseEntity<ApiResponse<Map<String, Object>>> triggerSettlementRun() {
        SettlementRun run = settlementAutomationScheduler.triggerManualRun();
        return ResponseEntity.ok(ApiResponse.success("Settlement run triggered", Map.of(
                "runId", run.getId(),
                "status", run.getStatus().name(),
                "restaurantsSettled", run.getRestaurantsSettled(),
                "agentsSettled", run.getAgentsSettled(),
                "totalAmount", run.getTotalAmount())));
    }
}
