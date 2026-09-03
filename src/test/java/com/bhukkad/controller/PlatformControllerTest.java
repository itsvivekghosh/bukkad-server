package com.bhukkad.controller;

import com.bhukkad.common.cache.LocalCacheService;
import com.bhukkad.config.ExternalEventsProperties;
import com.bhukkad.config.GeoIndexProperties;
import com.bhukkad.config.NotificationProperties;
import com.bhukkad.dto.response.ApiResponse;
import com.bhukkad.dto.response.CityConfigResponse;
import com.bhukkad.dto.response.TenantResponse;
import com.bhukkad.geo.RestaurantGeoIndexService;
import com.bhukkad.inventory.StockReservationService;
import com.bhukkad.tenant.TenantService;
import com.bhukkad.zone.CityConfigService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PlatformControllerTest {

    @Spy
    private ExternalEventsProperties externalEventsProperties = new ExternalEventsProperties();
    @Spy
    private GeoIndexProperties geoIndexProperties = new GeoIndexProperties();
    @Mock
    private LocalCacheService localCacheService;
    @Mock
    private RestaurantGeoIndexService restaurantGeoIndexService;
    @Mock
    private StockReservationService stockReservationService;
    @Spy
    private NotificationProperties notificationProperties = new NotificationProperties();
    @Mock
    private CityConfigService cityConfigService;
    @Mock
    private TenantService tenantService;

    @InjectMocks
    private PlatformController controller;

    @Test
    void getActiveCities_returnsCities() {
        List<CityConfigResponse> cities = List.of(CityConfigResponse.builder().build());
        when(cityConfigService.listActive()).thenReturn(cities);

        ResponseEntity<ApiResponse<List<CityConfigResponse>>> response = controller.getActiveCities();

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertSame(cities, response.getBody().getData());
        verify(cityConfigService).listActive();
    }

    @Test
    void getTenantByDomain_returnsTenant() {
        TenantResponse tenant = TenantResponse.builder().build();
        when(tenantService.getByDomain("acme.example.com")).thenReturn(tenant);

        ResponseEntity<ApiResponse<TenantResponse>> response = controller.getTenantByDomain("acme.example.com");

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertSame(tenant, response.getBody().getData());
        verify(tenantService).getByDomain("acme.example.com");
    }

    @Test
    void getPlatformStatus_includesKafkaDetailsWhenEnabled() {
        externalEventsProperties.setEnabled(true);
        externalEventsProperties.setType("kafka");
        when(localCacheService.getStats()).thenReturn(Map.of("hits", 5L));
        when(restaurantGeoIndexService.isEnabled()).thenReturn(true);
        when(stockReservationService.isEnabled()).thenReturn(true);

        ResponseEntity<ApiResponse<Map<String, Object>>> response = controller.getPlatformStatus();

        assertEquals(HttpStatus.OK, response.getStatusCode());
        Map<String, Object> data = response.getBody().getData();
        Map<String, Object> events = (Map<String, Object>) data.get("externalEvents");
        assertTrue((Boolean) events.get("enabled"));
        assertEquals("kafka", events.get("type"));
        assertTrue((Boolean) events.get("kafkaEnabled"));
        assertEquals("bhukkad.platform.events", events.get("kafkaTopic"));
        assertEquals("bhukkad-platform-consumer", events.get("kafkaConsumerGroup"));
        assertEquals("restaurants:geo", ((Map<String, Object>) data.get("redisGeo")).get("key"));
        Map<String, Object> notifications = (Map<String, Object>) data.get("notifications");
        assertTrue((Boolean) notifications.get("enabled"));
        assertFalse((Boolean) notifications.get("email"));
    }

    @Test
    void getPlatformStatus_omitsKafkaDetailsWhenDisabled() {
        externalEventsProperties.setEnabled(true);
        externalEventsProperties.setType("log");
        when(localCacheService.getStats()).thenReturn(Map.of());
        when(restaurantGeoIndexService.isEnabled()).thenReturn(false);
        when(stockReservationService.isEnabled()).thenReturn(false);

        ResponseEntity<ApiResponse<Map<String, Object>>> response = controller.getPlatformStatus();

        assertEquals(HttpStatus.OK, response.getStatusCode());
        Map<String, Object> data = response.getBody().getData();
        Map<String, Object> events = (Map<String, Object>) data.get("externalEvents");
        assertFalse((Boolean) events.get("kafkaEnabled"));
        assertFalse(events.containsKey("kafkaTopic"));
        assertFalse(events.containsKey("kafkaConsumerGroup"));
    }
}
