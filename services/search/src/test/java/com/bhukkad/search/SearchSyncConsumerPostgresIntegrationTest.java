package com.bhukkad.search;

import com.bhukkad.common.event.PlatformEventMessage;
import com.bhukkad.search.entity.MenuItemSearchEntity;
import com.bhukkad.search.entity.RestaurantSearchEntity;
import com.bhukkad.search.repository.MenuItemSearchRepository;
import com.bhukkad.search.repository.RestaurantSearchRepository;
import com.bhukkad.search.sync.SearchSyncEventConsumer;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * ADR-002 search sync against real PostgreSQL: restaurant menu mutations (as
 * event envelopes on {@code restaurant.events.v1}) update the
 * {@code restaurant_search}/{@code menu_item_search} rows, delete events
 * propagate, and re-delivered events are idempotent (SEARCH_SYNC scope
 * claims). Kafka is off in tests — the listener method is invoked directly
 * (AdminCqrsEventConsumer harness precedent); listener wiring is platform
 * config, covered elsewhere.
 */
@SpringBootTest(properties = {
        "app.events.external.enabled=false",
        "spring.autoconfigure.exclude="})
class SearchSyncConsumerPostgresIntegrationTest extends AbstractSearchPostgresTest {

    @Autowired
    private SearchSyncEventConsumer consumer;

    @Autowired
    private RestaurantSearchRepository restaurantSearchRepository;

    @Autowired
    private MenuItemSearchRepository menuItemSearchRepository;

    private final ObjectMapper objectMapper = new ObjectMapper();

    private String envelope(String type, long aggregateId, String payload) {
        return PlatformEventMessage.of(type, String.valueOf(aggregateId), payload).toJson();
    }

    @Test
    void restaurantUpdated_upsertsRestaurantSearchRow() {
        consumer.onRestaurantEvent(envelope("restaurant_updated", 301L, """
                {"id":301,"name":"Punjabi Tadka","description":"North Indian",
                 "imageUrl":"https://img/pt.png","isOpen":true,"isActive":true,
                 "averageRating":4.4,"totalReviews":120}
                """));

        RestaurantSearchEntity row = restaurantSearchRepository.findById(301L).orElseThrow();
        assertThat(row.getName()).isEqualTo("Punjabi Tadka");
        assertThat(row.getIsActive()).isTrue();
        assertThat(row.getAverageRating()).isEqualTo(4.4);
    }

    @Test
    void menuItemChanged_upsertsMenuItemSearchRow_contractTest() {
        consumer.onRestaurantEvent(envelope("restaurant_updated", 302L, """
                {"id":302,"name":"Dosa Corner","isActive":true,"isOpen":true}
                """));

        consumer.onRestaurantEvent(envelope("menu_item_changed", 4001L, """
                {"id":4001,"restaurantId":302,"name":"Masala Dosa",
                 "description":"Crispy crepe","price":89.50,"originalPrice":110.00,
                 "discountPercentage":18.64,"available":true,"foodType":"VEG",
                 "isVeg":true,"imageUrl":"https://img/dosa.png","preparationTime":15,
                 "bestseller":true,"restaurantName":"Dosa Corner"}
                """));

        MenuItemSearchEntity row = menuItemSearchRepository.findById(4001L).orElseThrow();
        assertThat(row.getName()).isEqualTo("Masala Dosa");
        assertThat(row.getRestaurantId()).isEqualTo(302L);
        assertThat(row.getPrice()).isEqualTo(89.5);
        assertThat(row.getRestaurantName()).isEqualTo("Dosa Corner");
        assertThat(row.getBestseller()).isTrue();

        // UPDATE propagation: the same id with a new name/price overwrites.
        consumer.onRestaurantEvent(envelope("menu_item_changed", 4001L, """
                {"id":4001,"restaurantId":302,"name":"Masala Dosa Deluxe",
                 "price":99.00,"available":true,"foodType":"VEG","isVeg":true,
                 "bestseller":false,"restaurantName":"Dosa Corner"}
                """));
        MenuItemSearchEntity updated = menuItemSearchRepository.findById(4001L).orElseThrow();
        assertThat(updated.getName()).isEqualTo("Masala Dosa Deluxe");
        assertThat(updated.getPrice()).isEqualTo(99.0);
        assertThat(updated.getBestseller()).isFalse();
    }

    @Test
    void redeliveredEvent_isIdempotentViaSearchSyncClaim() {
        String envelope = envelope("menu_item_changed", 4002L, """
                {"id":4002,"restaurantId":302,"name":"Idli Sambar","price":49.00,
                 "available":true,"foodType":"VEG","isVeg":true,"restaurantName":"Dosa Corner"}
                """);
        consumer.onRestaurantEvent(envelope);
        long rowsBefore = menuItemSearchRepository.count();

        consumer.onRestaurantEvent(envelope); // at-least-once redelivery

        assertThat(menuItemSearchRepository.count()).isEqualTo(rowsBefore);
        MenuItemSearchEntity row = menuItemSearchRepository.findById(4002L).orElseThrow();
        assertThat(row.getName()).isEqualTo("Idli Sambar");
    }

    @Test
    void menuItemDeleted_removesProjectionRow_noOrphanHits() {
        consumer.onRestaurantEvent(envelope("menu_item_changed", 4003L, """
                {"id":4003,"restaurantId":302,"name":"Vada","price":39.00,
                 "available":true,"foodType":"VEG","isVeg":true,"restaurantName":"Dosa Corner"}
                """));
        assertThat(menuItemSearchRepository.findById(4003L)).isPresent();

        consumer.onRestaurantEvent(envelope("menu_item_deleted", 4003L, "{\"id\":4003}"));

        assertThat(menuItemSearchRepository.findById(4003L)).isEmpty();
        // Deleting again (redelivery) is a no-op, not an error.
        consumer.onRestaurantEvent(envelope("menu_item_deleted", 4003L, "{\"id\":4003}"));
        assertThat(menuItemSearchRepository.findById(4003L)).isEmpty();
    }
}
