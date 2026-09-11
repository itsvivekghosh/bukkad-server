package com.bhukkad.delivery.api;

import com.bhukkad.common.error.BusinessException;
import com.bhukkad.common.scan.AllowFullScan;
import com.bhukkad.delivery.domain.CityConfig;
import com.bhukkad.delivery.domain.CityConfigRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Service-internal city registry (monolith parity): the delivery footprint
 * lives here ({@code city_configs}); the admin ops console proxies it.
 * Reachable only on the service mesh — the gateway never routes
 * {@code /internal/**}.
 */
@RestController
@RequestMapping("/api/v1/internal/cities")
@RequiredArgsConstructor
public class CityInternalController {

    private final CityConfigRepository cityConfigRepository;

    @GetMapping
    @Transactional(readOnly = true)
    @AllowFullScan(reason = "G-6 reviewed: city registry is a small bounded reference table (admin-managed)")
    public List<CityConfig> cities() {
        return cityConfigRepository.findAll();
    }

    @PostMapping
    @Transactional
    public Map<String, Object> createCity(@org.springframework.web.bind.annotation.RequestBody(
            required = false) Map<String, Object> body) {
        String name = body == null ? null : (String) body.get("name");
        if (name == null || name.isBlank()) {
            throw new BusinessException("name is required");
        }
        CityConfig city = new CityConfig();
        city.setCityName(name.trim());
        CityConfig saved = cityConfigRepository.save(city);
        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("id", saved.getId());
        resp.put("name", saved.getCityName());
        resp.put("currency", saved.getCurrency());
        resp.put("timezone", saved.getTimezone());
        return resp;
    }
}
