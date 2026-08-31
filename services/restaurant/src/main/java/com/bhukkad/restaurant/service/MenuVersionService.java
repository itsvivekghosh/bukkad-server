package com.bhukkad.restaurant.service;

import com.bhukkad.restaurant.domain.MenuVersion;
import com.bhukkad.restaurant.domain.MenuVersionRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;

/**
 * Versioned menu snapshots for audit/rollback (port of monolith
 * {@code MenuVersionService}).
 */
@Service
@RequiredArgsConstructor
public class MenuVersionService {

    private final MenuVersionRepository versionRepository;
    private final ObjectMapper objectMapper;

    @Transactional
    public MenuVersion snapshot(Long restaurantId, Map<String, Object> menu) {
        MenuVersion version = new MenuVersion();
        version.setRestaurantId(restaurantId);
        version.setVersion(nextVersion(restaurantId));
        version.setSnapshotJson(toJson(menu));
        return versionRepository.save(version);
    }

    @Transactional(readOnly = true)
    public List<MenuVersion> history(Long restaurantId) {
        return versionRepository.findByRestaurantIdOrderByVersionDesc(restaurantId);
    }

    private int nextVersion(Long restaurantId) {
        return versionRepository.findByRestaurantIdOrderByVersionDesc(restaurantId)
                .stream().findFirst().map(v -> v.getVersion() + 1).orElse(1);
    }

    private String toJson(Map<String, Object> menu) {
        try {
            return objectMapper.writeValueAsString(menu);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to serialize menu snapshot", e);
        }
    }
}