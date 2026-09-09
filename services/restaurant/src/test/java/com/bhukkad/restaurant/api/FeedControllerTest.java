package com.bhukkad.restaurant.api;

import com.bhukkad.common.cache.RedisCacheService;
import com.bhukkad.restaurant.domain.PromoBanner;
import com.bhukkad.restaurant.domain.PromoBannerRepository;
import com.bhukkad.restaurant.domain.Restaurant;
import com.bhukkad.restaurant.domain.RestaurantRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpHeaders;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * PERF-3 contract: the feed endpoint keeps the exact pre-cache HTTP behaviour
 * (bare Feed JSON body, quoted strong ETag over the content digest, 304 on a
 * matching If-None-Match, Cache-Control 30 s) while the projection + digest are
 * served from the cache — the DB load and SHA-256 run once per refresh.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class FeedControllerTest {

    @Mock private RestaurantRepository restaurantRepository;
    @Mock private PromoBannerRepository bannerRepository;
    @Mock private RedisCacheService redisCacheService;

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final Map<String, Object> backing = new HashMap<>();
    private MockMvc mockMvc;

    @SuppressWarnings("unchecked")
    @BeforeEach
    void wire() {
        backing.clear();
        // Faithful in-memory getOrCompute: serves the cached projection and
        // populates it exactly once per key until evicted.
        when(redisCacheService.getOrCompute(anyString(), eq(Map.class), anyLong(), any(Supplier.class)))
                .thenAnswer(inv -> {
                    String key = inv.getArgument(0);
                    Supplier<Object> loader = inv.getArgument(3);
                    Object hit = backing.get(key);
                    if (hit != null) {
                        return hit;
                    }
                    Object loaded = loader.get();
                    backing.put(key, loaded);
                    return loaded;
                });
        ObjectProvider<RedisCacheService> provider = new com.bhukkad.restaurant.testsupport.FixedObjectProvider<>(redisCacheService);
        FeedController controller = new FeedController(
                restaurantRepository, bannerRepository, objectMapper, provider);
        mockMvc = MockMvcBuilders.standaloneSetup(controller).build();

        Restaurant active = new Restaurant();
        active.setId(1L);
        active.setName("Dosa Corner");
        active.setAddress("1 MG Road");
        when(restaurantRepository.findByIsActiveTrue()).thenReturn(List.of(active));
        when(bannerRepository.findByActiveTrue()).thenReturn(List.<PromoBanner>of());
    }

    @Test
    void feed_servesProjectedBodyWith_quotedEtag_andDoesNotLeakEntities() throws Exception {
        MvcResult result = mockMvc.perform(get("/api/v1/feed"))
                .andExpect(status().isOk())
                .andExpect(header().exists(HttpHeaders.ETAG))
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, org.hamcrest.Matchers.containsString("max-age=30")))
                .andReturn();

        String body = result.getResponse().getContentAsString();
        assertThat(body).contains("Dosa Corner");
        Map<String, Object> json = objectMapper.readValue(body,
                new com.fasterxml.jackson.core.type.TypeReference<Map<String, Object>>() { });
        assertThat(json).containsOnlyKeys("restaurants", "banners");

        // Conditional GET against the SAME digest short-circuits to 304 (contract preserved).
        String etag = result.getResponse().getHeader(HttpHeaders.ETAG);
        mockMvc.perform(get("/api/v1/feed").header(HttpHeaders.IF_NONE_MATCH, etag))
                .andExpect(status().is(304))
                .andExpect(header().string(HttpHeaders.ETAG, etag));

        // And the second/third request never touched the DB or recomputed the hash:
        // the load supplier ran exactly once for the cached window.
        verify(restaurantRepository, times(1)).findByIsActiveTrue();
        verify(bannerRepository, times(1)).findByActiveTrue();
    }

    @Test
    void feed_weakValidatorAndRawDigestHeaders_stillMatch304() throws Exception {
        MvcResult first = mockMvc.perform(get("/api/v1/home/feed")).andExpect(status().isOk()).andReturn();
        String etag = first.getResponse().getHeader(HttpHeaders.ETAG); // "\"abc...\""

        mockMvc.perform(get("/api/v1/home/feed").header(HttpHeaders.IF_NONE_MATCH, "W/" + etag))
                .andExpect(status().is(304));
        mockMvc.perform(get("/api/v1/home/feed").header(HttpHeaders.IF_NONE_MATCH,
                        etag.replace("\"", "")))
                .andExpect(status().is(304));
    }

    @Test
    void feed_digestComputedOncePerRefresh_notPerRequest() throws Exception {
        String etag1 = mockMvc.perform(get("/api/v1/feed"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getHeader(HttpHeaders.ETAG);
        String etag2 = mockMvc.perform(get("/api/v1/feed"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getHeader(HttpHeaders.ETAG);

        assertThat(etag1).isEqualTo(etag2);
        // Supplier ran once for the projection; subsequent hits read the cache.
        verify(restaurantRepository, times(1)).findByIsActiveTrue();
    }

    @Test
    void feed_cacheEviction_recomputesDigestOnNextRequest() throws Exception {
        when(restaurantRepository.findByIsActiveTrue())
                .thenReturn(List.of())
                .thenReturn(List.of());
        mockMvc.perform(get("/api/v1/feed")).andExpect(status().isOk());
        backing.clear(); // simulates eviction via MenuCacheInvalidator.delete("feed:v1")
        mockMvc.perform(get("/api/v1/feed")).andExpect(status().isOk());

        verify(restaurantRepository, times(2)).findByIsActiveTrue();
    }

    @Test
    void feed_noProviderBean_behavesLikeBeforePerRequest() throws Exception {
        ObjectProvider<RedisCacheService> absent = new com.bhukkad.restaurant.testsupport.FixedObjectProvider<>(null);
        FeedController fallbackController = new FeedController(
                restaurantRepository, bannerRepository, objectMapper, absent);
        MockMvc noCache = MockMvcBuilders.standaloneSetup(fallbackController).build();

        MvcResult r1 = noCache.perform(get("/api/v1/feed")).andExpect(status().isOk()).andReturn();
        MvcResult r2 = noCache.perform(get("/api/v1/feed")).andExpect(status().isOk()).andReturn();
        assertThat(r1.getResponse().getHeader(HttpHeaders.ETAG))
                .isEqualTo(r2.getResponse().getHeader(HttpHeaders.ETAG));
        noCache.perform(get("/api/v1/feed")
                        .header(HttpHeaders.IF_NONE_MATCH, r1.getResponse().getHeader(HttpHeaders.ETAG)))
                .andExpect(status().is(304));
        // Without the cache, every request loads — including the 304 check,
        // which must recompute the digest to compare (previous behavior
        // maintained).
        verify(restaurantRepository, times(3)).findByIsActiveTrue();
    }
}
