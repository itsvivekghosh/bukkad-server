package com.bhukkad.restaurant.api;

import com.bhukkad.restaurant.domain.PromoBannerRepository;
import com.bhukkad.restaurant.domain.Restaurant;
import com.bhukkad.restaurant.domain.RestaurantRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
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
 */
@RestController
@RequiredArgsConstructor
public class FeedController {

    private final RestaurantRepository restaurantRepository;
    private final PromoBannerRepository bannerRepository;
    private final ObjectMapper objectMapper;

    @GetMapping({"/api/v1/feed", "/api/v1/feed/home", "/api/v1/home/feed", "/api/v1/mobile/feed"})
    public ResponseEntity<Feed> feed(
            @RequestHeader(value = HttpHeaders.IF_NONE_MATCH, required = false) String ifNoneMatch)
            throws Exception {
        Feed feed = loadFeed();
        String etag = digest(feed);
        if (etag.equals(normalize(ifNoneMatch))) {
            return ResponseEntity.status(304).eTag("\"" + etag + "\"").build();
        }
        return ResponseEntity.ok().eTag("\"" + etag + "\"").body(feed);
    }

    @GetMapping({"/api/v1/feed/banners", "/api/v1/home/banners"})
    public List<?> banners() {
        return bannerRepository.findByActiveTrue();
    }

    private Feed loadFeed() {
        List<Restaurant> restaurants = restaurantRepository.findAll().stream()
                .filter(r -> Boolean.TRUE.equals(r.getIsActive()))
                .toList();
        return new Feed(restaurants, bannerRepository.findByActiveTrue());
    }

    private String digest(Feed feed) throws Exception {
        byte[] json = objectMapper.writeValueAsBytes(feed);
        byte[] hash = MessageDigest.getInstance("SHA-256").digest(json);
        return HexFormat.of().formatHex(hash).substring(0, 32);
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

    public record Feed(List<Restaurant> restaurants, List<?> banners) {
    }
}
