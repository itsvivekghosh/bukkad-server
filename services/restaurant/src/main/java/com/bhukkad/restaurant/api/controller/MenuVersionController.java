package com.bhukkad.restaurant.api.controller;

import com.bhukkad.common.error.BusinessException;
import com.bhukkad.common.error.ResourceNotFoundException;
import com.bhukkad.common.error.UnauthorizedException;
import com.bhukkad.common.security.TokenPrincipal;
import com.bhukkad.restaurant.domain.entity.MenuVersion;
import com.bhukkad.restaurant.domain.repository.RestaurantRepository;
import com.bhukkad.restaurant.domain.service.impl.MenuVersionService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * Menu versioning (draft → publish → history) for the merchant app. Owner
 * routes authenticate via the JWT and verify restaurant ownership; admin
 * overrides. Unknown restaurants yield 404 (the kitchen app treats a bad id
 * as a data error, not an empty list).
 */
@RestController
@RequestMapping("/api/v1/menu/versions")
@RequiredArgsConstructor
public class MenuVersionController {

    private final MenuVersionService menuVersionService;
    private final RestaurantRepository restaurantRepository;
    private final RestaurantOwnerController ownerGuard;

    /** Stamps a draft snapshot of the restaurant's current menu. */
    @PostMapping
    @Transactional
    public MenuVersion snapshot(@AuthenticationPrincipal TokenPrincipal principal,
                                @RequestParam Long restaurantId,
                                @RequestParam(required = false) String label,
                                @RequestBody(required = false) Map<String, Object> menu) {
        ownerGuard.requireOwnerOrAdmin(principal, restaurantId);
        if (!restaurantRepository.existsById(restaurantId)) {
            throw new ResourceNotFoundException("Restaurant not found: " + restaurantId);
        }
        MenuVersion version = menuVersionService.snapshot(restaurantId,
                menu == null ? Map.of() : menu);
        if (label != null && !label.isBlank()) {
            version.setLabel(label.trim());
        }
        return version;
    }

    @GetMapping
    @Transactional(readOnly = true)
    public List<MenuVersion> history(@AuthenticationPrincipal TokenPrincipal principal,
                                     @RequestParam Long restaurantId) {
        ownerGuard.requireOwnerOrAdmin(principal, restaurantId);
        if (!restaurantRepository.existsById(restaurantId)) {
            throw new ResourceNotFoundException("Restaurant not found: " + restaurantId);
        }
        return menuVersionService.history(restaurantId);
    }

    /** Fetches a single version (draft or published). */
    @GetMapping("/{versionId}")
    @Transactional(readOnly = true)
    public MenuVersion get(@AuthenticationPrincipal TokenPrincipal principal,
                           @PathVariable Long versionId) {
        MenuVersion version = menuVersionService.get(versionId)
                .orElseThrow(() -> new ResourceNotFoundException("Menu version not found: " + versionId));
        ownerGuard.requireOwnerOrAdmin(principal, version.getRestaurantId());
        return version;
    }

    /** Promotes a draft to the restaurant's published version. */
    @PostMapping("/{versionId}/publish")
    @Transactional
    public MenuVersion publish(@AuthenticationPrincipal TokenPrincipal principal,
                               @PathVariable Long versionId) {
        MenuVersion version = menuVersionService.get(versionId)
                .orElseThrow(() -> new ResourceNotFoundException("Menu version not found: " + versionId));
        ownerGuard.requireOwnerOrAdmin(principal, version.getRestaurantId());
        if (MenuVersion.MenuVersionStatus.PUBLISHED.equals(version.getStatus())) {
            throw new BusinessException("Version " + versionId + " is already published");
        }
        return menuVersionService.publish(versionId);
    }

    /** Preview renders a version's snapshot without promoting it. */
    @GetMapping("/{versionId}/preview")
    @Transactional(readOnly = true)
    public Map<String, Object> preview(@AuthenticationPrincipal TokenPrincipal principal,
                                       @PathVariable Long versionId) {
        MenuVersion version = menuVersionService.get(versionId)
                .orElseThrow(() -> new ResourceNotFoundException("Menu version not found: " + versionId));
        ownerGuard.requireOwnerOrAdmin(principal, version.getRestaurantId());
        return menuVersionService.preview(versionId);
    }
}
