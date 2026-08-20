package com.bhukkad.controller;

import com.bhukkad.cache.LocalCacheService;
import com.bhukkad.config.ApiPaths;
import com.bhukkad.config.ExternalEventsProperties;
import com.bhukkad.config.GeoIndexProperties;
import com.bhukkad.config.NotificationProperties;
import com.bhukkad.dto.response.ApiResponse;
import com.bhukkad.dto.response.CityConfigResponse;
import com.bhukkad.dto.response.TenantResponse;
import com.bhukkad.inventory.StockReservationService;
import com.bhukkad.geo.RestaurantGeoIndexService;
import com.bhukkad.tenant.TenantService;
import com.bhukkad.zone.CityConfigService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping(ApiPaths.V1_PREFIX + "/platform")
@RequiredArgsConstructor
public class PlatformController {

    private final ExternalEventsProperties externalEventsProperties;
    private final GeoIndexProperties geoIndexProperties;
    private final LocalCacheService localCacheService;
    private final RestaurantGeoIndexService restaurantGeoIndexService;
    private final StockReservationService stockReservationService;
    private final NotificationProperties notificationProperties;
    private final CityConfigService cityConfigService;
    private final TenantService tenantService;

    /** Public list of active cities (Multi-city/Region Support). */
    @GetMapping("/cities")
    public ResponseEntity<ApiResponse<List<CityConfigResponse>>> getActiveCities() {
        return ResponseEntity.ok(ApiResponse.success(cityConfigService.listActive()));
    }

    /** Public white-label storefront config for a tenant domain. */
    @GetMapping("/tenants/{domain}")
    public ResponseEntity<ApiResponse<TenantResponse>> getTenantByDomain(@PathVariable String domain) {
        return ResponseEntity.ok(ApiResponse.success(tenantService.getByDomain(domain)));
    }

    @GetMapping("/status")
    public ResponseEntity<ApiResponse<Map<String, Object>>> getPlatformStatus() {
        Map<String, Object> status = new LinkedHashMap<>();

        Map<String, Object> events = new LinkedHashMap<>();
        events.put("enabled", externalEventsProperties.isEnabled());
        events.put("type", externalEventsProperties.getType());
        events.put("kafkaEnabled", externalEventsProperties.isKafkaEnabled());
        if (externalEventsProperties.isKafkaEnabled()) {
            events.put("kafkaTopic", externalEventsProperties.getKafka().getPlatformTopic());
            events.put("kafkaConsumerGroup", externalEventsProperties.getKafka().getConsumerGroup());
        }
        status.put("externalEvents", events);

        status.put("localCache", localCacheService.getStats());
        status.put("redisGeo", Map.of(
                "enabled", restaurantGeoIndexService.isEnabled(),
                "key", geoIndexProperties.getRestaurantsGeoKey()));
        status.put("stockReservation", Map.of(
                "enabled", stockReservationService.isEnabled()));
        status.put("notifications", Map.of(
                "enabled", notificationProperties.isEnabled(),
                "email", notificationProperties.getEmail().isEnabled(),
                "sms", notificationProperties.getSms().isEnabled(),
                "whatsapp", notificationProperties.getWhatsapp().isEnabled(),
                "push", notificationProperties.getPush().isEnabled()));

        return ResponseEntity.ok(ApiResponse.success("Platform status", status));
    }
}
