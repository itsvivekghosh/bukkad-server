package com.bhukkad.controller;

import com.bhukkad.admin.AdminOperationsDashboardService;
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
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AdminScaleControllerTest {

    @Mock
    private DeliveryZoneAdminService deliveryZoneAdminService;
    @Mock
    private PromotionAdminService promotionAdminService;
    @Mock
    private PromoBannerAdminService promoBannerAdminService;
    @Mock
    private SettlementAutomationScheduler settlementAutomationScheduler;
    @Mock
    private AdminOperationsDashboardService adminOperationsDashboardService;
    @Mock
    private CityConfigService cityConfigService;

    @InjectMocks
    private AdminScaleController controller;

    @Test
    void listZones_returnsAllZones() {
        List<DeliveryZoneResponse> zones = List.of(DeliveryZoneResponse.builder().build());
        when(deliveryZoneAdminService.listAll()).thenReturn(zones);

        ResponseEntity<ApiResponse<List<DeliveryZoneResponse>>> response = controller.listZones();

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertSame(zones, response.getBody().getData());
        verify(deliveryZoneAdminService).listAll();
    }

    @Test
    void createZone_returnsCreatedZone() {
        DeliveryZoneRequest request = new DeliveryZoneRequest();
        DeliveryZoneResponse zone = DeliveryZoneResponse.builder().build();
        when(deliveryZoneAdminService.create(request)).thenReturn(zone);

        ResponseEntity<ApiResponse<DeliveryZoneResponse>> response = controller.createZone(request);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals("Zone created", response.getBody().getMessage());
        assertSame(zone, response.getBody().getData());
    }

    @Test
    void updateZone_returnsUpdatedZone() {
        DeliveryZoneRequest request = new DeliveryZoneRequest();
        DeliveryZoneResponse zone = DeliveryZoneResponse.builder().build();
        when(deliveryZoneAdminService.update(7L, request)).thenReturn(zone);

        ResponseEntity<ApiResponse<DeliveryZoneResponse>> response = controller.updateZone(7L, request);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals("Zone updated", response.getBody().getMessage());
        assertSame(zone, response.getBody().getData());
    }

    @Test
    void deleteZone_returnsSuccess() {
        ResponseEntity<ApiResponse<Void>> response = controller.deleteZone(7L);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals("Zone deleted", response.getBody().getMessage());
        verify(deliveryZoneAdminService).delete(7L);
    }

    @Test
    void listCities_returnsAllCities() {
        List<CityConfigResponse> cities = List.of(CityConfigResponse.builder().build());
        when(cityConfigService.listAll()).thenReturn(cities);

        ResponseEntity<ApiResponse<List<CityConfigResponse>>> response = controller.listCities();

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertSame(cities, response.getBody().getData());
    }

    @Test
    void createCity_returnsCreatedCity() {
        CityConfigRequest request = new CityConfigRequest();
        CityConfigResponse city = CityConfigResponse.builder().build();
        when(cityConfigService.create(request)).thenReturn(city);

        ResponseEntity<ApiResponse<CityConfigResponse>> response = controller.createCity(request);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals("City config created", response.getBody().getMessage());
        assertSame(city, response.getBody().getData());
    }

    @Test
    void updateCity_returnsUpdatedCity() {
        CityConfigRequest request = new CityConfigRequest();
        CityConfigResponse city = CityConfigResponse.builder().build();
        when(cityConfigService.update(3L, request)).thenReturn(city);

        ResponseEntity<ApiResponse<CityConfigResponse>> response = controller.updateCity(3L, request);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals("City config updated", response.getBody().getMessage());
        assertSame(city, response.getBody().getData());
    }

    @Test
    void deleteCity_returnsSuccess() {
        ResponseEntity<ApiResponse<Void>> response = controller.deleteCity(3L);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals("City config deleted", response.getBody().getMessage());
        verify(cityConfigService).delete(3L);
    }

    @Test
    void listCampaigns_returnsAllCampaigns() {
        List<PromotionCampaignResponse> campaigns = List.of(PromotionCampaignResponse.builder().build());
        when(promotionAdminService.listAll()).thenReturn(campaigns);

        ResponseEntity<ApiResponse<List<PromotionCampaignResponse>>> response = controller.listCampaigns();

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertSame(campaigns, response.getBody().getData());
    }

    @Test
    void createCampaign_returnsCreatedCampaign() {
        PromotionCampaignRequest request = new PromotionCampaignRequest();
        PromotionCampaignResponse campaign = PromotionCampaignResponse.builder().build();
        when(promotionAdminService.create(request)).thenReturn(campaign);

        ResponseEntity<ApiResponse<PromotionCampaignResponse>> response = controller.createCampaign(request);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals("Campaign created", response.getBody().getMessage());
        assertSame(campaign, response.getBody().getData());
    }

    @Test
    void updateCampaign_returnsUpdatedCampaign() {
        PromotionCampaignRequest request = new PromotionCampaignRequest();
        PromotionCampaignResponse campaign = PromotionCampaignResponse.builder().build();
        when(promotionAdminService.update(5L, request)).thenReturn(campaign);

        ResponseEntity<ApiResponse<PromotionCampaignResponse>> response = controller.updateCampaign(5L, request);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals("Campaign updated", response.getBody().getMessage());
        assertSame(campaign, response.getBody().getData());
    }

    @Test
    void deactivateCampaign_returnsSuccess() {
        ResponseEntity<ApiResponse<Void>> response = controller.deactivateCampaign(5L);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals("Campaign deactivated", response.getBody().getMessage());
        verify(promotionAdminService).deactivate(5L);
    }

    @Test
    void listBanners_returnsAllBanners() {
        List<PromoBannerResponse> banners = List.of(PromoBannerResponse.builder().build());
        when(promoBannerAdminService.listAll()).thenReturn(banners);

        ResponseEntity<ApiResponse<List<PromoBannerResponse>>> response = controller.listBanners();

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertSame(banners, response.getBody().getData());
    }

    @Test
    void createBanner_returnsCreatedBanner() {
        PromoBannerRequest request = new PromoBannerRequest();
        PromoBannerResponse banner = PromoBannerResponse.builder().build();
        when(promoBannerAdminService.create(request)).thenReturn(banner);

        ResponseEntity<ApiResponse<PromoBannerResponse>> response = controller.createBanner(request);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals("Banner created", response.getBody().getMessage());
        assertSame(banner, response.getBody().getData());
    }

    @Test
    void updateBanner_returnsUpdatedBanner() {
        PromoBannerRequest request = new PromoBannerRequest();
        PromoBannerResponse banner = PromoBannerResponse.builder().build();
        when(promoBannerAdminService.update(2L, request)).thenReturn(banner);

        ResponseEntity<ApiResponse<PromoBannerResponse>> response = controller.updateBanner(2L, request);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals("Banner updated", response.getBody().getMessage());
        assertSame(banner, response.getBody().getData());
    }

    @Test
    void getOperationsDashboard_returnsDashboard() {
        AdminOperationsDashboardResponse dashboard = AdminOperationsDashboardResponse.builder().build();
        when(adminOperationsDashboardService.getDashboard()).thenReturn(dashboard);

        ResponseEntity<ApiResponse<AdminOperationsDashboardResponse>> response = controller.getOperationsDashboard();

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertSame(dashboard, response.getBody().getData());
    }

    @Test
    void triggerSettlementRun_returnsRunSummary() {
        SettlementRun run = new SettlementRun();
        run.setId(11L);
        run.setStatus(SettlementRun.RunStatus.COMPLETED);
        run.setRestaurantsSettled(3);
        run.setAgentsSettled(2);
        run.setTotalAmount(4500.0);
        when(settlementAutomationScheduler.triggerManualRun()).thenReturn(run);

        ResponseEntity<ApiResponse<Map<String, Object>>> response = controller.triggerSettlementRun();

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals("Settlement run triggered", response.getBody().getMessage());
        Map<String, Object> data = response.getBody().getData();
        assertEquals(11L, data.get("runId"));
        assertEquals("COMPLETED", data.get("status"));
        assertEquals(3, data.get("restaurantsSettled"));
        assertEquals(2, data.get("agentsSettled"));
        assertEquals(4500.0, data.get("totalAmount"));
    }
}
