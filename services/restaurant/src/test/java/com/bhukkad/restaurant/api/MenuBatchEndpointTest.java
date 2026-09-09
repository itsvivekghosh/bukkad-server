package com.bhukkad.restaurant.api;

import com.bhukkad.common.cache.RedisCacheService;
import com.bhukkad.common.error.BusinessException;
import com.bhukkad.restaurant.domain.CuisineRepository;
import com.bhukkad.restaurant.domain.MenuCategoryRepository;
import com.bhukkad.restaurant.domain.MenuItem;
import com.bhukkad.restaurant.domain.MenuItemRepository;
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
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Supplier;
import java.util.stream.LongStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyIterable;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * PERF-3 item 4: the bounded batch chord surface {@code GET
 * /api/v1/menu/items?ids=1,2,3} — 100-id cap (over cap → 400) and per-id
 * 60 s cache ({@code menu:item:<id>}) through RedisCacheService.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class MenuBatchEndpointTest {

    @Mock private RestaurantRepository restaurantRepository;
    @Mock private MenuItemRepository menuItemRepository;
    @Mock private MenuCategoryRepository menuCategoryRepository;
    @Mock private CuisineRepository cuisineRepository;
    @Mock private RedisCacheService redisCacheService;

    private final Map<String, Object> backing = new HashMap<>();
    private MockMvc mockMvc;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @SuppressWarnings("unchecked")
    @BeforeEach
    void wire() {
        backing.clear();
        when(redisCacheService.getOrCompute(anyString(), eq(String.class), anyLong(), any(Supplier.class)))
                .thenAnswer(inv -> {
                    String key = inv.getArgument(0);
                    if (backing.containsKey(key)) {
                        return backing.get(key);
                    }
                    Object loaded = ((Supplier<Object>) inv.getArgument(3)).get();
                    if (loaded != null) {
                        backing.put(key, loaded);
                    }
                    return loaded;
                });
        mockMvc = MockMvcBuilders.standaloneSetup(controller(true)).build();
    }

    private PublicBrowseController controller(boolean withCache) {
        ObjectProvider<RedisCacheService> provider =
                new com.bhukkad.restaurant.testsupport.FixedObjectProvider<>(
                        withCache ? redisCacheService : null);
        return new PublicBrowseController(restaurantRepository, menuItemRepository,
                menuCategoryRepository, cuisineRepository, provider, objectMapper);
    }

    private MenuItem item(long id, boolean available) {
        MenuItem mi = new MenuItem();
        mi.setId(id);
        mi.setRestaurantId(id * 3);
        mi.setName("Item-" + id);
        mi.setPrice(new BigDecimal("49.90"));
        mi.setIsAvailable(available);
        return mi;
    }

    @Test
    void overCap_rejectedExactlyAt101Ids() {
        // BusinessException is mapped to HTTP 400 by the shared
        // GlobalExceptionHandler; asserted at the service boundary because the
        // standalone MockMvc slice runs without the advice.
        String ids = LongStream.rangeClosed(1, 101).mapToObj(String::valueOf)
                .collect(java.util.stream.Collectors.joining(","));

        org.assertj.core.api.Assertions.assertThatThrownBy(
                        () -> controller(true).batchMenuItems(ids))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("maximum is 100");

        // 100 ids is at the cap and must pass the guard (empty result here —
        // all cold ids resolve to nothing in this fixture).
        controller(true).batchMenuItems(LongStream.rangeClosed(1, 100)
                .mapToObj(String::valueOf).collect(java.util.stream.Collectors.joining(",")));
    }

    @Test
    void malformedId_rejected_withoutTouchingDb() {
        org.assertj.core.api.Assertions.assertThatThrownBy(
                        () -> controller(true).batchMenuItems("1,oops"))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("Invalid id");
        verify(menuItemRepository, org.mockito.Mockito.never()).findById(anyLong());
    }

    @Test
    void cachedBatch_perIdShortCacheServesRepeatRequestsOneDbLoadPerId() throws Exception {
        when(menuItemRepository.findById(7L)).thenReturn(Optional.of(item(7L, true)));
        when(menuItemRepository.findById(8L)).thenReturn(Optional.of(item(8L, false)));
        when(menuItemRepository.findById(9L)).thenReturn(Optional.empty());

        String body1 = mockMvc.perform(get("/api/v1/menu/items").param("ids", "7,8,9"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        String body2 = mockMvc.perform(get("/api/v1/menu/items").param("ids", "7,8,9"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        Map<String, Object> json = objectMapper.readValue(body1,
                new com.fasterxml.jackson.core.type.TypeReference<Map<String, Object>>() { });
        assertThat(json).containsOnlyKeys("items");
        List<?> items = (List<?>) json.get("items");
        // 9 missing, 8 filtered as unavailable; only 7 served — and the response
        // shape matches the pre-cache contract exactly on repeat hits.
        assertThat(items).hasSize(1);
        assertThat(body2).isEqualTo(body1);
        verify(menuItemRepository, times(1)).findById(7L);
        // 60 s per-id TTL, key scheme menu:item:<id> (one cache lookup per request):
        verify(redisCacheService, times(2)).getOrCompute(eq("menu:item:7"), eq(String.class),
                eq(60L), any(Supplier.class));
    }

    @Test
    void withoutCache_keepsLegacyDbBatchPathAndShape() throws Exception {
        when(menuItemRepository.findAllById(org.mockito.ArgumentMatchers.anyIterable()))
                .thenReturn(List.of(item(1L, true)));
        MockMvc direct = MockMvcBuilders.standaloneSetup(controller(false)).build();

        String body = direct.perform(get("/api/v1/menu/items").param("ids", "1,2"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();


        Map<?, ?> json = objectMapper.readValue(body, Map.class);
        assertThat((List<?>) json.get("items")).hasSize(1); // 2 is absent (not found)
        verify(menuItemRepository, times(1)).findAllById(anyIterable());
    }

    @Test
    void emptyAndAbsentIds_shortCircuit() throws Exception {
        mockMvc.perform(get("/api/v1/menu/items"))
                .andExpect(status().isOk());
        mockMvc.perform(get("/api/v1/menu/items").param("ids", ""))
                .andExpect(status().isOk());
    }
}
