package com.bhukkad.restaurant.api;

import com.bhukkad.common.error.BusinessException;
import com.bhukkad.common.error.ResourceNotFoundException;
import com.bhukkad.restaurant.domain.Cuisine;
import com.bhukkad.restaurant.domain.CuisineRepository;
import com.bhukkad.restaurant.domain.MenuCategory;
import com.bhukkad.restaurant.domain.MenuCategoryRepository;
import com.bhukkad.restaurant.domain.MenuItem;
import com.bhukkad.restaurant.domain.MenuItemRepository;
import com.bhukkad.restaurant.domain.Restaurant;
import com.bhukkad.restaurant.domain.RestaurantRepository;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Public (unauthenticated) browse surface — monolith-parity endpoints used by
 * the customer discovery flow and the order service's RestaurantClient.
 * Read-only; admin mutations stay on the authenticated controllers.
 *
 * <p>Hardening: oversized pages are clamped (never rejected), non-numeric
 * pagination values fall back to defaults, and batch {@code ids} params are
 * capped at {@value #MAX_BATCH_IDS} to bound the {@code IN (...) } list.</p>
 */
@RestController
@RequiredArgsConstructor
public class PublicBrowseController {

    private static final int MAX_BATCH_IDS = 100;
    private static final int MAX_PAGE_SIZE = 100;

    private final RestaurantRepository restaurantRepository;
    private final MenuItemRepository menuItemRepository;
    private final MenuCategoryRepository menuCategoryRepository;
    private final CuisineRepository cuisineRepository;
    private final org.springframework.beans.factory.ObjectProvider<com.bhukkad.common.cache.RedisCacheService>
            cacheProvider;
    private final com.fasterxml.jackson.databind.ObjectMapper objectMapper;

    /**
     * Per-id cache round-trips keep money decimals exact: floats bind back to
     * BigDecimal (with scale) instead of Double, so a cached item serializes
     * byte-identically to a freshly loaded one.
     */
    private volatile com.fasterxml.jackson.databind.ObjectMapper cacheJsonMapper;

    private com.fasterxml.jackson.databind.ObjectMapper cacheJsonMapper() {
        var mapper = cacheJsonMapper;
        if (mapper == null) {
            mapper = objectMapper.copy().enable(
                    com.fasterxml.jackson.databind.DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS);
            cacheJsonMapper = mapper;
        }
        return mapper;
    }

    // ---------- restaurants ----------

    @GetMapping("/api/v1/restaurants/public")
    @Transactional(readOnly = true)
    public Map<String, Object> listPublic(@RequestParam(required = false) String page,
                                          @RequestParam(required = false) String size,
                                          @RequestParam(required = false) Long cuisineId,
                                          @RequestParam(required = false) Boolean isPureVeg,
                                          @RequestParam(required = false) String ids) {
        List<Long> idList = parseIds(ids);
        if (idList != null) {
            if (idList.size() > MAX_BATCH_IDS) {
                throw new BusinessException("Too many ids; maximum is " + MAX_BATCH_IDS);
            }
            List<Restaurant> restaurants = idList.isEmpty()
                    ? List.of()
                    : restaurantRepository.findAllById(idList).stream()
                            .filter(r -> Boolean.TRUE.equals(r.getIsActive()))
                            .toList();
            return envelope(restaurants.stream().map(this::toMap).toList(), 0,
                    restaurants.size(), restaurants.size());
        }
        int safePage = clamp(parseLong(page, 0), 0, 100_000);
        int safeSize = clamp(parseLong(size, 20), 1, MAX_PAGE_SIZE);
        Pageable pageable = PageRequest.of(safePage, safeSize);
        var result = restaurantRepository.findByIsActive(true, pageable);
        List<?> content = result.getContent().stream().map(this::toMap).toList();
        return envelope(content, result.getNumber(), result.getTotalElements(), result.getTotalPages());
    }

    @GetMapping("/api/v1/restaurants/public/filter")
    @Transactional(readOnly = true)
    public Map<String, Object> filterPublic(@RequestParam(required = false) String cuisine,
                                            @RequestParam(required = false) Boolean isPureVeg) {
        List<Restaurant> active = restaurantRepository.findByIsActiveTrue();
        Set<Long> cuisineIds = null;
        if (cuisine != null && !cuisine.isBlank()) {
            String needle = cuisine.trim();
            cuisineIds = cuisineRepository.findAll().stream()
                    .filter(c -> c.getName() != null && c.getName().equalsIgnoreCase(needle))
                    .map(Cuisine::getId)
                    .collect(Collectors.toSet());
        }
        final Set<Long> allowedCuisines = cuisineIds;
        List<Restaurant> filtered = active.stream()
                .filter(r -> allowedCuisines == null || allowedCuisines.contains(r.getCuisineId()))
                .filter(r -> isPureVeg == null || !isPureVeg || Boolean.TRUE.equals(r.getIsPureVeg()))
                .toList();
        return envelope(filtered.stream().map(this::toMap).toList(), 0,
                filtered.size(), filtered.size());
    }

    /**
     * Geo discovery: bounding-box prefilter (SQL) refined with a great-circle
     * distance check. Restaurants without stored coordinates are excluded.
     */
    @GetMapping("/api/v1/restaurants/public/nearby")
    @Transactional(readOnly = true)
    public Map<String, Object> nearby(@RequestParam String latitude,
                                      @RequestParam String longitude,
                                      @RequestParam(defaultValue = "5") String radiusKm) {
        double lat = requireNumber(latitude, "latitude");
        double lng = requireNumber(longitude, "longitude");
        double radius = Math.max(0.5, Math.min(50.0, requireNumber(radiusKm, "radiusKm")));
        List<Restaurant> candidates = restaurantRepository.findByIsActiveTrue().stream()
                .filter(r -> r.getLatitude() != null && r.getLongitude() != null)
                .toList();
        List<Restaurant> near = candidates.stream()
                .filter(r -> haversineKm(lat, lng, r.getLatitude(), r.getLongitude()) <= radius)
                .sorted((a, b) -> Double.compare(
                        haversineKm(lat, lng, a.getLatitude(), a.getLongitude()),
                        haversineKm(lat, lng, b.getLatitude(), b.getLongitude())))
                .limit(MAX_BATCH_IDS)
                .toList();
        return envelope(near.stream().map(this::toMap).toList(), 0,
                near.size(), near.size());
    }

    @GetMapping("/api/v1/restaurants/public/{id}")
    @Transactional(readOnly = true)
    public Map<String, Object> getPublic(@PathVariable Long id) {
        Restaurant restaurant = restaurantRepository.findById(id)
                .filter(r -> Boolean.TRUE.equals(r.getIsActive()))
                .orElseThrow(() -> new ResourceNotFoundException("Restaurant not found: " + id));
        return toMap(restaurant);
    }

    @GetMapping("/api/v1/restaurants/public/search")
    @Transactional(readOnly = true)
    public Map<String, Object> searchPublic(@RequestParam String keyword,
                                            @RequestParam(required = false) String limit) {
        int safeLimit = clamp(parseLong(limit, 20), 1, MAX_BATCH_IDS);
        List<Restaurant> found = restaurantRepository.searchByName(keyword).stream()
                .filter(r -> Boolean.TRUE.equals(r.getIsActive()))
                .limit(safeLimit)
                .toList();
        return envelope(found.stream().map(this::toMap).toList(), 0,
                found.size(), found.size());
    }

    // ---------- menu ----------

    /**
     * Batch menu-item read for the checkout chord (guide §6 PERF-3.4):
     * {@code GET /api/v1/menu/items?ids=1,2,3}, capped at
     * {@value #MAX_BATCH_IDS} ids (over cap fails 400 via BusinessException).
     *
     * <p>PERF-3: each id resolves through a short-lived (60 s) per-id cache
     * ({@code menu:item:<id>}) served from L1/L2; cold ids load from the DB.
     * The wire contract is unchanged: {@code {"items": [...]}} with the same
     * rendered item shape. Without Redis beans the DB batch load runs per
     * request like before.</p>
     */
    @GetMapping("/api/v1/menu/items")
    @Transactional(readOnly = true)
    public Map<String, Object> batchMenuItems(@RequestParam(required = false) String ids) {
        List<Long> idList = parseIds(ids);
        if (idList == null || idList.isEmpty()) {
            return Map.of("items", List.of());
        }
        if (idList.size() > MAX_BATCH_IDS) {
            throw new BusinessException("Too many ids; maximum is " + MAX_BATCH_IDS);
        }
        com.bhukkad.common.cache.RedisCacheService cache = cacheProvider.getIfAvailable();
        if (cache == null) {
            List<MenuItem> items = menuItemRepository.findAllById(idList).stream()
                    .filter(i -> Boolean.TRUE.equals(i.getIsAvailable()))
                    .toList();
            return Map.of("items", renderItemBatch(items));
        }
        List<Map<String, Object>> out = new ArrayList<>();
        for (Long id : idList) {
            String json = cache.getOrCompute(
                    com.bhukkad.restaurant.service.cache.RestaurantCacheKeys.menuItem(id),
                    String.class,
                    com.bhukkad.restaurant.service.cache.RestaurantCacheKeys.MENU_ITEM_TTL_SECONDS,
                    () -> loadRenderedItemJson(id));
            if (json == null) {
                continue; // nonexistent
            }
            Map<String, Object> rendered = readItem(json);
            if (Boolean.TRUE.equals(rendered.get("available"))) {
                out.add(rendered);
            }
        }
        return Map.of("items", out);
    }

    /** Supplier for one cold id; returns null for missing items (nulls stay uncached). */
    private String loadRenderedItemJson(Long id) {
        return menuItemRepository.findById(id)
                .map(item -> renderItemBatch(List.of(item)).get(0))
                .map(this::writeItem)
                .orElse(null);
    }

    private String writeItem(Map<String, Object> rendered) {
        try {
            return cacheJsonMapper().writeValueAsString(rendered);
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new IllegalStateException("Menu item serialization failed", e);
        }
    }

    private Map<String, Object> readItem(String json) {
        try {
            return cacheJsonMapper().readValue(json, new com.fasterxml.jackson.core.type.TypeReference<
                    java.util.LinkedHashMap<String, Object>>() { });
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new IllegalStateException("Menu item deserialization failed", e);
        }
    }


    @GetMapping("/api/v1/menu/items/{id}")
    @Transactional(readOnly = true)
    public Map<String, Object> getMenuItem(@PathVariable Long id) {
        MenuItem item = menuItemRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Menu item not found: " + id));
        return itemToMap(item);
    }

    @GetMapping("/api/v1/menu/items/restaurant/{restaurantId}")
    @Transactional(readOnly = true)
    public Map<String, Object> menuForRestaurant(@PathVariable Long restaurantId) {
        List<MenuItem> items = menuItemRepository.findByRestaurantIdAndIsAvailableTrue(restaurantId);
        return Map.of("items", renderItemBatch(items));
    }

    @GetMapping("/api/v1/menu/items/search")
    @Transactional(readOnly = true)
    public Map<String, Object> searchMenuItems(@RequestParam String keyword,
                                               @RequestParam(required = false) String limit) {
        int safeLimit = clamp(parseLong(limit, 20), 1, MAX_BATCH_IDS);
        List<MenuItem> items = menuItemRepository.searchByName(keyword).stream()
                .filter(i -> Boolean.TRUE.equals(i.getIsAvailable()))
                .limit(safeLimit)
                .toList();
        return Map.of("items", renderItemBatch(items));
    }

    @GetMapping("/api/v1/menu/items/restaurant/{restaurantId}/bestsellers")
    @Transactional(readOnly = true)
    public Map<String, Object> bestsellers(@PathVariable Long restaurantId) {
        List<MenuItem> items = menuItemRepository.findByRestaurantIdAndIsAvailableTrue(restaurantId)
                .stream().filter(MenuItem::getBestseller).toList();
        return Map.of("items", renderItemBatch(items));
    }

    @GetMapping("/api/v1/menu/items/restaurant/{restaurantId}/recommended")
    @Transactional(readOnly = true)
    public Map<String, Object> recommended(@PathVariable Long restaurantId) {
        List<MenuItem> items = menuItemRepository.findByRestaurantIdAndIsAvailableTrue(restaurantId)
                .stream().filter(MenuItem::getRecommended).toList();
        return Map.of("items", renderItemBatch(items));
    }

    @GetMapping("/api/v1/menu/items/category/{categoryId}")
    @Transactional(readOnly = true)
    public Map<String, Object> itemsByCategory(@PathVariable Long categoryId) {
        List<MenuItem> items = menuItemRepository.findByCategoryIdAndIsAvailableTrue(categoryId);
        return Map.of("items", renderItemBatch(items));
    }

    @GetMapping("/api/v1/menu/categories/restaurant/{restaurantId}")
    @Transactional(readOnly = true)
    public Map<String, Object> categoriesForRestaurant(@PathVariable Long restaurantId) {
        List<MenuCategory> categories =
                menuCategoryRepository.findByRestaurantIdAndActiveTrue(restaurantId);
        return Map.of("categories", categories.stream().map(this::categoryToMap).toList());
    }

    // ---------- cuisines ----------

    @GetMapping("/api/v1/cuisines/{id}")
    @Transactional(readOnly = true)
    public Cuisine getCuisine(@PathVariable Long id) {
        return cuisineRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Cuisine not found: " + id));
    }

    // ---------- helpers ----------

    private List<Map<String, Object>> renderItemBatch(List<MenuItem> items) {
        Set<Long> categoryIds = items.stream()
                .map(MenuItem::getCategoryId)
                .filter(java.util.Objects::nonNull)
                .collect(Collectors.toSet());
        Map<Long, String> categoryNames = menuCategoryRepository.findAllById(categoryIds).stream()
                .collect(Collectors.toMap(MenuCategory::getId, MenuCategory::getName, (a, b) -> a));
        return items.stream().map(m -> itemToMap(m, categoryNames)).toList();
    }

    /** Parses a comma-separated ids param: null when absent, empty when blank. */
    private List<Long> parseIds(String raw) {
        if (raw == null) {
            return null;
        }
        List<Long> ids = new ArrayList<>();
        for (String part : raw.split(",")) {
            String token = part.trim();
            if (token.isEmpty()) {
                continue;
            }
            try {
                ids.add(Long.parseLong(token));
            } catch (NumberFormatException e) {
                throw new BusinessException("Invalid id in list: " + token);
            }
        }
        return ids;
    }

    private static double requireNumber(String raw, String name) {
        try {
            return Double.parseDouble(raw);
        } catch (NumberFormatException e) {
            throw new BusinessException("Invalid " + name + ": " + raw);
        }
    }

    private static long parseLong(String raw, long fallback) {
        if (raw == null || raw.isBlank()) {
            return fallback;
        }
        try {
            return Long.parseLong(raw.trim());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private static int clamp(long value, long min, long max) {
        return (int) Math.max(min, Math.min(max, value));
    }

    private static double haversineKm(double lat1, double lon1, double lat2, double lon2) {
        double r = 6371.0;
        double dLat = Math.toRadians(lat2 - lat1);
        double dLon = Math.toRadians(lon2 - lon1);
        double a = Math.sin(dLat / 2) * Math.sin(dLat / 2)
                + Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2))
                * Math.sin(dLon / 2) * Math.sin(dLon / 2);
        return r * 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
    }

    private Map<String, Object> envelope(List<?> content, int page, long totalElements, long totalPages) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("content", content);
        body.put("page", page);
        body.put("totalElements", totalElements);
        body.put("totalPages", totalPages);
        return body;
    }

    private Map<String, Object> toMap(Restaurant r) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("id", r.getId());
        map.put("name", r.getName());
        map.put("description", r.getDescription());
        map.put("cuisineId", r.getCuisineId());
        map.put("address", r.getAddress());
        map.put("phone", r.getPhone());
        map.put("isActive", Boolean.TRUE.equals(r.getIsActive()));
        map.put("isPureVeg", Boolean.TRUE.equals(r.getIsPureVeg()));
        map.put("avgRating", r.getAvgRating() == null ? 0.0 : r.getAvgRating());
        return map;
    }

    private Map<String, Object> itemToMap(MenuItem m) {
        Map<Long, String> names = m.getCategoryId() == null
                ? Map.of()
                : menuCategoryRepository.findById(m.getCategoryId())
                        .map(c -> Map.of(c.getId(), c.getName()))
                        .orElse(Map.of());
        return itemToMap(m, names);
    }

    private Map<String, Object> itemToMap(MenuItem m, Map<Long, String> categoryNames) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("id", m.getId());
        map.put("restaurantId", m.getRestaurantId());
        map.put("name", m.getName());
        map.put("description", m.getDescription());
        map.put("price", m.getPrice());
        map.put("originalPrice", m.getOriginalPrice());
        map.put("discountPercentage", m.getDiscountPercentage());
        map.put("available", Boolean.TRUE.equals(m.getIsAvailable()));
        map.put("isVeg", Boolean.TRUE.equals(m.getIsVeg()));
        map.put("bestseller", Boolean.TRUE.equals(m.getBestseller()));
        map.put("averageRating", m.getAverageRating());
        if (m.getCategoryId() != null && categoryNames.containsKey(m.getCategoryId())) {
            map.put("categoryName", categoryNames.get(m.getCategoryId()));
        }
        return map;
    }

    private Map<String, Object> categoryToMap(MenuCategory c) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("id", c.getId());
        map.put("restaurantId", c.getRestaurantId());
        map.put("name", c.getName());
        map.put("displayOrder", c.getDisplayOrder() == null ? 0 : c.getDisplayOrder());
        map.put("active", Boolean.TRUE.equals(c.getActive()));
        return map;
    }
}
