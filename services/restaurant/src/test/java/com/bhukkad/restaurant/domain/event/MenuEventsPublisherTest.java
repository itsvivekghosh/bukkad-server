package com.bhukkad.restaurant.domain.event;

import com.bhukkad.common.event.PlatformEventMessage;
import com.bhukkad.common.outbox.OutboxClient;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;

/**
 * W2-ORDER-SEARCH restaurant → search event contracts (ADR-002): the
 * {@code MenuEventsPublisher} enqueues the exact payload shapes the search
 * {@code SearchSyncEventConsumer} parses, and enqueue failures PROPAGATE
 * (G-1) so the menu mutation rolls back with the event.
 */
@ExtendWith(MockitoExtension.class)
class MenuEventsPublisherTest {

    @Mock
    private OutboxClient outboxClient;

    private final ObjectMapper objectMapper = new ObjectMapper();
    private MenuEventsPublisher publisher;

    @BeforeEach
    void setUp() {
        publisher = new MenuEventsPublisher(outboxClient, objectMapper);
    }

    @Test
    void restaurantUpdated_payloadMatchesSearchConsumerContract() throws Exception {
        com.bhukkad.restaurant.domain.entity.Restaurant restaurant =
                new com.bhukkad.restaurant.domain.entity.Restaurant();
        restaurant.setId(7L);
        restaurant.setName("Curry House");
        restaurant.setDescription("Indian");
        restaurant.setImageUrl("https://img/ch.png");
        restaurant.setIsOpen(true);
        restaurant.setIsActive(false);
        restaurant.setAvgRating(4.2);
        restaurant.setTotalReviews(88);

        publisher.restaurantUpdated(restaurant);

        ArgumentCaptor<PlatformEventMessage> captor =
                ArgumentCaptor.forClass(PlatformEventMessage.class);
        verify(outboxClient).enqueue(captor.capture(), eq(7L));
        PlatformEventMessage message = captor.getValue();
        assertThat(message.eventType()).isEqualTo(MenuEventsPublisher.TYPE_RESTAURANT_UPDATED);
        var node = objectMapper.readTree(message.payload());
        assertThat(node.path("id").asLong()).isEqualTo(7L);
        assertThat(node.path("name").asText()).isEqualTo("Curry House");
        assertThat(node.path("description").asText()).isEqualTo("Indian");
        assertThat(node.path("imageUrl").asText()).isEqualTo("https://img/ch.png");
        assertThat(node.path("isOpen").asBoolean()).isTrue();
        assertThat(node.path("isActive").asBoolean()).isFalse();
        assertThat(node.path("averageRating").asDouble()).isEqualTo(4.2);
        assertThat(node.path("totalReviews").asInt()).isEqualTo(88);
    }

    @Test
    void menuItemChanged_payloadMatchesSearchConsumerContract() throws Exception {
        com.bhukkad.restaurant.domain.entity.MenuItem item =
                new com.bhukkad.restaurant.domain.entity.MenuItem();
        item.setId(42L);
        item.setRestaurantId(7L);
        item.setName("Butter Chicken");
        item.setDescription("Creamy");
        item.setPrice(new java.math.BigDecimal("289.50"));
        item.setIsAvailable(true);
        item.setIsVeg(false);
        item.setBestseller(true);
        item.setPreparationTime(20);

        publisher.menuItemChanged(item, "Curry House");

        ArgumentCaptor<PlatformEventMessage> captor =
                ArgumentCaptor.forClass(PlatformEventMessage.class);
        verify(outboxClient).enqueue(captor.capture(), eq(42L));
        var node = objectMapper.readTree(captor.getValue().payload());
        assertThat(node.path("id").asLong()).isEqualTo(42L);
        assertThat(node.path("restaurantId").asLong()).isEqualTo(7L);
        assertThat(node.path("name").asText()).isEqualTo("Butter Chicken");
        assertThat(node.path("price").decimalValue()).isEqualByComparingTo("289.50");
        assertThat(node.path("available").asBoolean()).isTrue();
        assertThat(node.path("isVeg").asBoolean()).isFalse();
        assertThat(node.path("bestseller").asBoolean()).isTrue();
        assertThat(node.path("preparationTime").asInt()).isEqualTo(20);
        assertThat(node.path("restaurantName").asText()).isEqualTo("Curry House");
    }

    @Test
    void menuItemDeleted_payloadCarriesOnlyTheId() throws Exception {
        publisher.menuItemDeleted(99L);

        ArgumentCaptor<PlatformEventMessage> captor =
                ArgumentCaptor.forClass(PlatformEventMessage.class);
        verify(outboxClient).enqueue(captor.capture(), eq(99L));
        assertThat(captor.getValue().eventType()).isEqualTo(MenuEventsPublisher.TYPE_MENU_ITEM_DELETED);
        var node = objectMapper.readTree(captor.getValue().payload());
        assertThat(node.path("id").asLong()).isEqualTo(99L);
    }

    @Test
    void enqueueFailure_propagatesSoMenuMutationRollsBack() {
        doThrow(new IllegalStateException("G-1 violation"))
                .when(outboxClient).enqueue(any(PlatformEventMessage.class), any(Long.class));

        com.bhukkad.restaurant.domain.entity.MenuItem item = new com.bhukkad.restaurant.domain.entity.MenuItem();
        item.setId(42L);
        item.setRestaurantId(7L);
        item.setName("X");

        assertThatThrownBy(() -> publisher.menuItemChanged(item, null))
                .isInstanceOf(IllegalStateException.class);
    }
}
