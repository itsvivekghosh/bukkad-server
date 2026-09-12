package com.bhukkad.restaurant.api.controller;

import com.bhukkad.common.cache.RedisCacheService;
import com.bhukkad.restaurant.domain.repository.PromoBannerRepository;
import com.bhukkad.restaurant.domain.repository.RestaurantRepository;
import com.bhukkad.restaurant.infrastructure.cache.RestaurantCacheKeys;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

/**
 * Home/mobile feed (port of monolith {@code HomeFeedController} +
 * {@code MobileFeedController}): active restaurants + active promo banners.
 *
 * <p>Served on the canonical {@code /api/v1/feed} path and the public
 * gateway-parity aliases {@code /api/v1/home/*} and {@code /api/v1/mobile/feed}.
 * The composite response is ETag-tagged over its own content digest, so
 * conditional GETs cheaply short-circuit to 304. Mobile BFF versioning is
 * handled by {@code VersionHeaderFilter} (Accept-Version / X-API-Version).</p>
 *
 * <p>PERF-3: the projection (body bytes + precomputed ETag digest) is cached
 * server-side for ~45 s through {@link RedisCacheService}, and invalidated by
 * restaurant activation changes ({@code MenuCacheInvalidator}). The SHA-256
 * digest is computed once per refresh instead of on every request, and the
 * database is no longer hit per uncached-conditional GET. The HTTP contract
 * (path, JSON body, ETag format, 304 behaviour, Cache-Control) is unchanged;
 * without a Redis bean the endpoint falls back to the direct load.</p>
 */
@RestController
@RequiredArgsConstructor
public class FeedController {

    private final RestaurantRepository restaurantRepository;
    private final PromoBannerRepository bannerRepository;
    private final ObjectMapper objectMapper;
    private final ObjectProvider<RedisCacheService> cacheProvider;

    @GetMapping({"/api/v1/feed", "/api/v1/feed/home", "/api/v1/home/feed", "/api/v1/mobile/feed"})
    public ResponseEntity<String> feed(
            @RequestHeader(value = HttpHeaders.IF_NONE_MATCH, required = false) String ifNoneMatch) {
        EtaggedFeed etagged = loadFeedEtagged();
        String etag = etagged.digest();
        if (etag.equals(normalize(ifNoneMatch))) {
            return ResponseEntity.status(304).eTag("\"" + etag + "\"").build();
        }
        return ResponseEntity.ok().eTag("\"" + etag + "\"")
                .cacheControl(org.springframework.http.CacheControl.maxAge(java.time.Duration.ofSeconds(30)))
                .contentType(MediaType.APPLICATION_JSON)
                .body(etagged.bodyJson());
    }

    @GetMapping({"/api/v1/feed/banners", "/api/v1/home/banners"})
    public List<?> banners() {
        return bannerRepository.findByActiveTrue();
    }

    /** Digest + serialized body pair; the digest is derived from the exact bytes served. */
    record EtaggedFeed(String digest, String bodyJson) {
    }

    private static final String CACHE_ETAG_FIELD = "etag";
    private static final String CACHE_BODY_FIELD = "body";

    /**
     * Serves the projection from the L1/L2 cache when available; otherwise
     * (no Redis wired in the context, or Redis fully down) computes it per
     * request exactly like before PERF-3.
     */
    private EtaggedFeed loadFeedEtagged() {
        RedisCacheService cache = cacheProvider.getIfAvailable();
        if (cache == null) {
            return buildProjection();
        }
        Map<String, Object> projection = cache.getOrCompute(
                RestaurantCacheKeys.FEED, Map.class,
                RestaurantCacheKeys.FEED_TTL_SECONDS, this::cachedProjection);
        return new EtaggedFeed(String.valueOf(projection.get(CACHE_ETAG_FIELD)),
                String.valueOf(projection.get(CACHE_BODY_FIELD)));
    }

    private Map<String, Object> cachedProjection() {
        EtaggedFeed built = buildProjection();
        Map<String, Object> map = new LinkedHashMap<>();
        map.put(CACHE_ETAG_FIELD, built.digest());
        map.put(CACHE_BODY_FIELD, built.bodyJson());
        return map;
    }

    /**
     * Loads active restaurants into public projections. The previous version
     * serialized raw entities: the lazy {@code features}/{@code galleryImages}/
     * {@code foodTypes} collections threw LazyInitializationException outside
     * the transaction (500 on every feed request), and full entities also
     * leaked ownerId/commission/FSSAI fields publicly.
     */
    private EtaggedFeed buildProjection() {
        try {
            Feed feed = loadFeed();
            String json = objectMapper.writeValueAsString(feed);
            byte[] hash = MessageDigest.getInstance("SHA-256")
                    .digest(json.getBytes(StandardCharsets.UTF_8));
            String digest = HexFormat.of().formatHex(hash).substring(0, 32);
            return new EtaggedFeed(digest, json);
        } catch (java.io.IOException | java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException("Feed projection failed", e);
        }
    }

    private Feed loadFeed() {
        List<FeedRestaurant> restaurants = restaurantRepository.findByIsActiveTrue().stream()
                .map(r -> new FeedRestaurant(r.getId(), r.getName(), r.getAddress(), r.getImageUrl()))
                .toList();
        return new Feed(restaurants, bannerRepository.findByActiveTrue());
    }

    private static String normalize(String headerValue) {
        if (headerValue == null) {
            return null;
        }
        String v = headerValue.trim();
        if (v.startsWith("W/")) {
            v = v.substring(2);
        }
        if (v.length() >= 2 && v.charAt(0) == '"' && v.charAt(v.length() - 1) == '"') {
            v = v.substring(1, v.length() - 1);
        }
        return Objects.equals(v, "") ? null : v;
    }

    /** Public projection — no PII, no lazy associations. */
    public record FeedRestaurant(Long id, String name, String address, String imageUrl) {}

    public record Feed(List<FeedRestaurant> restaurants, List<?> banners) {
    }
}
