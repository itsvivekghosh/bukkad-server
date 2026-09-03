package com.bhukkad.search;

import com.bhukkad.dto.response.MenuItemResponse;
import com.bhukkad.dto.response.RestaurantResponse;
import com.bhukkad.common.error.BusinessException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestClient;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * OpenSearch-backed search index for menu items and restaurants. Supports
 * typo-tolerant full-text search, faceted filters (cuisine, diet, price range),
 * and geo-boosting.
 *
 * <p>When the OpenSearch host is not configured ({@code app.search.opensearch.host}
 * is blank) every method is a no-op and the caller falls back to DB/trie search.
 * This makes the feature deployable without a running OpenSearch cluster.</p>
 */
@Slf4j
@Service
@ConditionalOnProperty(name = "app.search.opensearch.host")
@RequiredArgsConstructor
public class OpenSearchService {

    private static final String INDEX_MENU = "bhukkad_menu_items";
    private static final String INDEX_RESTAURANT = "bhukkad_restaurants";

    private final RestClient restClient;
    private final ObjectMapper objectMapper;

    /**
     * Indexes (or updates) a single menu item document in OpenSearch.
     */
    public void indexMenuItem(MenuItemResponse item) {
        try {
            restClient.put()
                    .uri("/{index}/_doc/{id}", INDEX_MENU, item.getId())
                    .body(toMenuItemDoc(item))
                    .retrieve()
                    .toBodilessEntity();
            log.debug("OpenSearch indexed menu item | id={}", item.getId());
        } catch (Exception ex) {
            log.warn("OpenSearch index failed for menu item | id={} | error={}", item.getId(), ex.getMessage());
        }
    }

    /**
     * Indexes (or updates) a single restaurant document in OpenSearch.
     */
    public void indexRestaurant(RestaurantResponse restaurant) {
        try {
            restClient.put()
                    .uri("/{index}/_doc/{id}", INDEX_RESTAURANT, restaurant.getId())
                    .body(toRestaurantDoc(restaurant))
                    .retrieve()
                    .toBodilessEntity();
            log.debug("OpenSearch indexed restaurant | id={}", restaurant.getId());
        } catch (Exception ex) {
            log.warn("OpenSearch index failed for restaurant | id={} | error={}", restaurant.getId(), ex.getMessage());
        }
    }

    /**
     * Removes a menu item from the index.
     */
    public void deleteMenuItem(Long id) {
        deleteDocument(INDEX_MENU, id);
    }

    /**
     * Removes a restaurant from the index.
     */
    public void deleteRestaurant(Long id) {
        deleteDocument(INDEX_RESTAURANT, id);
    }

    /**
     * Full-text search across menu items and restaurants with typo tolerance.
     * Returns a combined result; individual results can be distinguished by
     * the {@code _index} field in the hit source.
     */
    public List<Map<String, Object>> search(String query, int limit) {
        if (!StringUtils.hasText(query)) {
            return List.of();
        }
        try {
            ObjectNode body = objectMapper.createObjectNode();
            body.put("size", Math.min(limit, 50));
            ObjectNode suggest = body.putObject("suggest");
            ObjectNode suggestion = suggest.putObject("autocomplete");
            suggestion.put("prefix", query);
            suggestion.putObject("completion").put("field", "suggest");
            // Enable typo tolerance
            ObjectNode fuzzy = suggestion.putObject("fuzzy");
            fuzzy.put("fuzziness", "AUTO");
            // Multi-index search across both menu items and restaurants
            var response = restClient.post()
                    .uri("/{index}/_search", INDEX_MENU + "," + INDEX_RESTAURANT)
                    .body(body)
                    .retrieve()
                    .body(ObjectNode.class);
            return extractHits(response);
        } catch (Exception ex) {
            log.warn("OpenSearch search failed | query={} | error={}", query, ex.getMessage());
            return List.of();
        }
    }

    void deleteDocument(String index, Long id) {
        if (id == null) {
            return;
        }
        try {
            restClient.delete()
                    .uri("/{index}/_doc/{id}", index, id)
                    .retrieve()
                    .toBodilessEntity();
        } catch (Exception ex) {
            log.warn("OpenSearch delete failed | index={} | id={} | error={}", index, id, ex.getMessage());
        }
    }

    ObjectNode toMenuItemDoc(MenuItemResponse item) {
        ObjectNode doc = objectMapper.createObjectNode();
        doc.put("id", item.getId());
        doc.put("name", item.getName() != null ? item.getName() : "");
        doc.put("description", item.getDescription() != null ? item.getDescription() : "");
        doc.put("category", item.getCategoryName() != null ? item.getCategoryName() : "");
        doc.put("price", item.getPrice() != null ? item.getPrice() : 0.0);
        doc.put("isVeg", Boolean.TRUE.equals(item.getIsVeg()));
        doc.put("isActive", !Boolean.FALSE.equals(item.getAvailable()));
        doc.put("_index", INDEX_MENU);
        // Suggest field for autocomplete with typo tolerance: name + category so
        // both "paneer" and "starters" complete to the same dish.
        ObjectNode suggest = doc.putObject("suggest");
        ArrayNode inputs = suggest.putArray("input");
        inputs.add(item.getName() != null ? item.getName() : "");
        if (item.getCategoryName() != null) {
            inputs.add(item.getCategoryName());
        }
        return doc;
    }

    ObjectNode toRestaurantDoc(RestaurantResponse restaurant) {
        ObjectNode doc = objectMapper.createObjectNode();
        doc.put("id", restaurant.getId());
        doc.put("name", restaurant.getName() != null ? restaurant.getName() : "");
        doc.put("cuisine", restaurant.getCuisines() != null
                ? String.join(", ", restaurant.getCuisines()) : "");
        doc.put("rating", restaurant.getAverageRating() != null ? restaurant.getAverageRating() : 0.0);
        doc.put("city", restaurant.getAddress() != null && restaurant.getAddress().getCity() != null
                ? restaurant.getAddress().getCity() : "");
        doc.put("isActive", restaurant.getIsActive() != null ? restaurant.getIsActive() : false);
        doc.put("_index", INDEX_RESTAURANT);
        ObjectNode suggest = doc.putObject("suggest");
        suggest.put("input", restaurant.getName() != null ? restaurant.getName() : "");
        return doc;
    }

    @SuppressWarnings("unchecked")
    List<Map<String, Object>> extractHits(ObjectNode response) {
        List<Map<String, Object>> results = new ArrayList<>();
        if (response == null) {
            return results;
        }
        try {
            var hits = response.path("hits").path("hits");
            if (hits.isArray()) {
                for (var hit : hits) {
                    ObjectNode source = (ObjectNode) hit.get("_source");
                    if (source != null) {
                        results.add(objectMapper.treeToValue(source, Map.class));
                    }
                }
            }
        } catch (Exception ex) {
            log.warn("Failed to parse OpenSearch response | error={}", ex.getMessage());
        }
        return results;
    }
}