package com.bhukkad.restaurant.service;

import com.bhukkad.restaurant.domain.MenuVersion;
import com.bhukkad.restaurant.domain.MenuVersionRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Versioned menu snapshots for audit/rollback.
 *
 * <p>Port of the monolith {@code com.bhukkad.menu.MenuVersionService}. The
 * restaurant-service variant keeps the snapshot as a JSON document keyed by
 * restaurant so other services can preview/publish without depending on the
 * full menu-category/menu-item entity graph.
 */
@Service
@RequiredArgsConstructor
public class MenuVersionService {

    private final MenuVersionRepository versionRepository;
    private final ObjectMapper objectMapper;

    /**
     * Records a snapshot of the supplied menu document at the next version number.
     */
    @Transactional
    public MenuVersion snapshot(Long restaurantId, Map<String, Object> menu) {
        MenuVersion version = new MenuVersion();
        version.setRestaurantId(restaurantId);
        version.setVersion(nextVersion(restaurantId));
        version.setSnapshotJson(toJson(menu));
        version.setCreatedAt(LocalDateTime.now());
        version.setStatus(MenuVersion.MenuVersionStatus.DRAFT);
        return versionRepository.save(version);
    }

    @Transactional(readOnly = true)
    public List<MenuVersion> history(Long restaurantId) {
        return versionRepository.findByRestaurantIdOrderByVersionDesc(restaurantId);
    }

    /**
     * Parses the snapshot JSON of a stored version back into a map for previews.
     */
    @Transactional(readOnly = true)
    public Map<String, Object> preview(Long versionId) {
        MenuVersion version = versionRepository.findById(versionId)
                .orElseThrow(() -> new IllegalArgumentException("Menu version not found: " + versionId));
        try {
            return objectMapper.readValue(version.getSnapshotJson(),
                    new TypeReference<LinkedHashMap<String, Object>>() {});
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to parse menu snapshot", e);
        }
    }

    @Transactional
    public MenuVersion publish(Long versionId) {
        MenuVersion version = versionRepository.findById(versionId)
                .orElseThrow(() -> new IllegalArgumentException("Menu version not found: " + versionId));
        version.setStatus(MenuVersion.MenuVersionStatus.PUBLISHED);
        version.setPublishedAt(LocalDateTime.now());
        return versionRepository.save(version);
    }

    public Optional<MenuVersion> latestVersion(Long restaurantId) {
        return versionRepository.findTopByRestaurantIdOrderByVersionDesc(restaurantId);
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