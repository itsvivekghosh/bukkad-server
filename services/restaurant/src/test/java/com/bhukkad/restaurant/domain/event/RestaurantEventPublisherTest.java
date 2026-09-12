package com.bhukkad.restaurant.domain.event;

import com.bhukkad.common.event.PlatformEventMessage;
import com.bhukkad.common.outbox.OutboxClient;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;

/**
 * Restaurant domain events flow through the transactional outbox.
 */
@ExtendWith(MockitoExtension.class)
class RestaurantEventPublisherTest {

    @Mock private OutboxClient outboxClient;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private RestaurantEventPublisher publisher;

    @BeforeEach
    void setUp() {
        publisher = new RestaurantEventPublisher(outboxClient, objectMapper);
    }

    @Test
    void restaurantCreated_enqueuesRestaurantCreatedEvent() {
        publisher.restaurantCreated(5L, "Test");

        verify(outboxClient).enqueue(argThat(msg -> {
            assertThat(msg.eventType()).isEqualTo(RestaurantEventPublisher.TYPE_RESTAURANT_CREATED);
            assertThat(msg.payload()).contains("\"id\":5").contains("\"name\":\"Test\"");
            return true;
        }), eq(5L));
    }

    @Test
    void availabilityChanged_enqueuesAvailabilityEvent() {
        publisher.availabilityChanged(5L, true);

        verify(outboxClient).enqueue(argThat(msg -> {
            assertThat(msg.eventType()).isEqualTo(RestaurantEventPublisher.TYPE_AVAILABILITY_CHANGED);
            assertThat(msg.payload()).contains("\"active\":true");
            return true;
        }), eq(5L));
    }

    @Test
    void menuChanged_enqueuesMenuChangedEvent() {
        publisher.menuChanged(5L, 42L);

        verify(outboxClient).enqueue(argThat(msg -> {
            assertThat(msg.eventType()).isEqualTo(RestaurantEventPublisher.TYPE_MENU_CHANGED);
            assertThat(msg.payload()).contains("\"restaurantId\":5").contains("\"menuItemId\":42");
            return true;
        }), eq(5L));
    }

    @Test
    void enqueueFailure_swallowed() {
        // The publisher must not break the business transaction when the outbox
        // write fails — the event is best-effort and logged.
        org.mockito.Mockito.doThrow(new RuntimeException("outbox down"))
                .when(outboxClient).enqueue(any(PlatformEventMessage.class), any(Long.class));

        publisher.menuChanged(5L, 42L);
    }
}