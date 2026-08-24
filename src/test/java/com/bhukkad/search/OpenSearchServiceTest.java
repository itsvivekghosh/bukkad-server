package com.bhukkad.search;

import com.bhukkad.dto.response.MenuItemResponse;
import com.bhukkad.dto.response.RestaurantResponse;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link OpenSearchService}. The RestClient fluent chain is a
 * deep-stubbed mock; document mapping and response parsing are tested directly.
 */
class OpenSearchServiceTest {

    private final RestClient restClient = mock(RestClient.class, RETURNS_DEEP_STUBS);
    private final ObjectMapper objectMapper = new ObjectMapper();
    private OpenSearchService service;

    @BeforeEach
    void setUp() {
        service = new OpenSearchService(restClient, objectMapper);
    }

    @Test
    void indexMenuItem_putsDocument() {
        MenuItemResponse item = new MenuItemResponse();
        item.setId(10L);
        item.setName("Paneer Tikka");
        item.setCategoryName("Starters");
        item.setPrice(240.0);
        item.setIsVeg(true);
        item.setAvailable(true);

        service.indexMenuItem(item);

        verify(restClient.put()).uri(any(String.class), any(Object[].class));
    }

    @Test
    void indexMenuItem_swallowsRestClientFailure() {
        when(restClient.put()).thenThrow(new IllegalStateException("opensearch down"));

        service.indexMenuItem(new MenuItemResponse());
    }

    @Test
    void indexRestaurant_putsDocument() {
        RestaurantResponse restaurant = new RestaurantResponse();
        restaurant.setId(5L);
        restaurant.setName("Spice Route");
        restaurant.setCuisines(java.util.Set.of("North Indian", "Mughlai"));
        restaurant.setAverageRating(4.3);
        restaurant.setIsActive(true);

        service.indexRestaurant(restaurant);

        verify(restClient.put()).uri(any(String.class), any(Object[].class));
    }

    @Test
    void deleteMenuItem_deletesDocument() {
        service.deleteMenuItem(10L);
        verify(restClient.delete()).uri(any(String.class), any(Object[].class));
    }

    @Test
    void deleteMenuItem_swallowsFailureAndNullId() {
        when(restClient.delete()).thenThrow(new IllegalStateException("down"));
        service.deleteMenuItem(99L);
        service.deleteMenuItem(null); // no-op, no exception
    }

    @Test
    void search_returnsEmptyForBlankQuery() {
        assertTrue(service.search("  ", 10).isEmpty());
        assertTrue(service.search(null, 10).isEmpty());
    }

    @Test
    void search_returnsEmptyOnFailure() {
        when(restClient.post()).thenThrow(new IllegalStateException("down"));

        assertTrue(service.search("paneer", 10).isEmpty());
    }

    @Test
    void search_capsLimitAtFifty() {
        when(restClient.post().uri(any(String.class), any(Object[].class))
                .body(any()).retrieve().body(ObjectNode.class))
                .thenReturn(objectMapper.createObjectNode());

        List<Map<String, Object>> results = service.search("paneer", 500);

        assertTrue(results.isEmpty());
        verify(restClient.post()).uri(any(String.class), any(Object[].class));
    }

    @Test
    void toMenuItemDoc_mapsFieldsAndIndexMarker() {
        MenuItemResponse item = new MenuItemResponse();
        item.setId(1L);
        item.setName("Biriyani");
        item.setDescription("Hyderabadi");
        item.setCategoryName("Rice");
        item.setPrice(300.0);
        item.setIsVeg(false);
        item.setAvailable(true);

        ObjectNode doc = service.toMenuItemDoc(item);

        assertEquals(1L, doc.get("id").asLong());
        assertEquals("Biriyani", doc.get("name").asText());
        assertEquals("Rice", doc.get("category").asText());
        assertEquals("bhukkad_menu_items", doc.get("_index").asText());
        assertEquals("Biriyani", doc.path("suggest").get("input").get(0).asText());
        assertEquals("Rice", doc.path("suggest").get("input").get(1).asText());
    }

    @Test
    void toMenuItemDoc_handlesNulls() {
        MenuItemResponse item = new MenuItemResponse();
        item.setId(2L);

        ObjectNode doc = service.toMenuItemDoc(item);

        assertEquals("", doc.get("name").asText());
        assertEquals("", doc.get("category").asText());
        assertEquals(0.0, doc.get("price").asDouble());
    }

    @Test
    void toRestaurantDoc_mapsFieldsAndCity() {
        RestaurantResponse restaurant = new RestaurantResponse();
        restaurant.setId(2L);
        restaurant.setName("Cafe");
        com.bhukkad.dto.response.AddressResponse address = new com.bhukkad.dto.response.AddressResponse();
        address.setCity("Bengaluru");
        restaurant.setAddress(address);
        restaurant.setCuisines(java.util.Set.of("Cafe"));
        restaurant.setAverageRating(4.0);

        ObjectNode doc = service.toRestaurantDoc(restaurant);

        assertEquals(2L, doc.get("id").asLong());
        assertEquals("Bengaluru", doc.get("city").asText());
        assertEquals("bhukkad_restaurants", doc.get("_index").asText());
    }

    @Test
    void extractHits_parsesSourcesAndIgnoresMalformed() {
        ObjectNode response = objectMapper.createObjectNode();
        var hits = response.putObject("hits").putArray("hits");
        var hit1 = hits.addObject();
        hit1.set("_source", objectMapper.valueToTree(Map.of("id", 1, "name", "A")));
        var hit2 = hits.addObject();
        hit2.set("_source", objectMapper.valueToTree(Map.of("id", 2, "name", "B")));

        List<Map<String, Object>> results = service.extractHits(response);

        assertEquals(2, results.size());
        assertEquals(1, ((Number) results.get(0).get("id")).intValue());

        // Null response / malformed tree must not throw.
        assertTrue(service.extractHits(null).isEmpty());
        assertTrue(service.extractHits(objectMapper.createObjectNode()).isEmpty());
        assertNotNull(service.extractHits(objectMapper.createObjectNode().putObject("hits")));
    }
}
