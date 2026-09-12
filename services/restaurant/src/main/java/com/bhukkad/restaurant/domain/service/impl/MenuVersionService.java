package com.bhukkad.restaurant.domain.service.impl;

import com.bhukkad.restaurant.domain.entity.MenuVersion;
import com.bhukkad.restaurant.domain.repository.MenuVersionRepository;
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
    private final org.springframework.transaction.support.TransactionTemplate txTemplate;

    private static final int SNAPSHOT_RETRY_LIMIT = 3;

    /**
     * Records a snapshot at the next version number. Version allocation is a
     * read-then-insert race; the unique index
     * (V5__menu_version_unique.sql) is the source of truth, so on a clash the
     * attempt is re-run in a FRESH transaction (a violation aborts the PG
     * transaction it fired in — in-txn retry would keep failing).
     */
    public MenuVersion snapshot(Long restaurantId, Map<String, Object> menu) {
        String json = toJson(menu);
        org.springframework.transaction.support.TransactionTemplate requiresNew =
                new org.springframework.transaction.support.TransactionTemplate(
                        txTemplate.getTransactionManager());
        requiresNew.setPropagationBehavior(
                org.springframework.transaction.TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        for (int attempt = 1; ; attempt++) {
            try {
                return requiresNew.execute(status -> {
                    MenuVersion version = new MenuVersion();
                    version.setRestaurantId(restaurantId);
                    version.setVersion(nextVersion(restaurantId));
                    version.setSnapshotJson(json);
                    version.setCreatedAt(LocalDateTime.now());
                    version.setStatus(MenuVersion.MenuVersionStatus.DRAFT);
                    return versionRepository.saveAndFlush(version);
                });
            } catch (org.springframework.dao.DataIntegrityViolationException race) {
                if (attempt >= SNAPSHOT_RETRY_LIMIT) {
                    throw new com.bhukkad.common.error.BusinessException(
                            "Menu snapshot conflict — too much concurrent publishing, retry shortly");
                }
            }
        }
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

    public java.util.Optional<MenuVersion> get(Long versionId) {
        return versionRepository.findById(versionId);
    }

    public Optional<MenuVersion> latestVersion(Long restaurantId) {        return versionRepository.findTopByRestaurantIdOrderByVersionDesc(restaurantId);
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