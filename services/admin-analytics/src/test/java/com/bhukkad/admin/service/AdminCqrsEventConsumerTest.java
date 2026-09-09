package com.bhukkad.admin.service;

import com.bhukkad.admin.domain.RestaurantOrderStat;
import com.bhukkad.admin.domain.RestaurantOrderStatRepository;
import com.bhukkad.common.event.PlatformEventMessage;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Unit test for {@link AdminCqrsEventConsumer} — verifies the CQRS projection
 * of {@code OrderCreated} events into the {@code restaurant_order_stats} read
 * model.
 */
@ExtendWith(MockitoExtension.class)
class AdminCqrsEventConsumerTest {

    private final ObjectMapper objectMapper = new ObjectMapper().registerModule(new JavaTimeModule());

    @Mock
    private RestaurantOrderStatRepository statRepository;

    @Captor
    private ArgumentCaptor<RestaurantOrderStat> statCaptor;

    private AdminCqrsEventConsumer consumer;

    @BeforeEach
    void setUp() {
        consumer = new AdminCqrsEventConsumer(statRepository, objectMapper);
    }

    @Test
    void orderCreatedEvent_projectsNewReadModel() throws Exception {
        when(statRepository.findById(10L)).thenReturn(Optional.empty());

        PlatformEventMessage event = PlatformEventMessage.of(
                "OrderCreated", "42",
                "{\"orderId\":42,\"customerId\":7,\"restaurantId\":10,\"totalAmount\":29.50}");

        consumer.onOrderEvent(objectMapper.writeValueAsString(event));

        verify(statRepository).save(statCaptor.capture());
        RestaurantOrderStat stat = statCaptor.getValue();
        assertThat(stat.getRestaurantId()).isEqualTo(10L);
        assertThat(stat.getOrderCount()).isEqualTo(1L);
        assertThat(stat.getRevenue()).isEqualByComparingTo(new BigDecimal("29.50"));
    }

    @Test
    void orderCreatedEvent_incrementsExistingReadModel() throws Exception {
        RestaurantOrderStat existing = new RestaurantOrderStat();
        existing.setRestaurantId(10L);
        existing.setOrderCount(3L);
        existing.setRevenue(new BigDecimal("100.00"));
        when(statRepository.findById(10L)).thenReturn(Optional.of(existing));

        PlatformEventMessage event = PlatformEventMessage.of(
                "OrderCreated", "43",
                "{\"orderId\":43,\"customerId\":8,\"restaurantId\":10,\"totalAmount\":10.00}");

        consumer.onOrderEvent(objectMapper.writeValueAsString(event));

        verify(statRepository).save(statCaptor.capture());
        RestaurantOrderStat stat = statCaptor.getValue();
        assertThat(stat.getOrderCount()).isEqualTo(4L);
        assertThat(stat.getRevenue()).isEqualByComparingTo(new BigDecimal("110.00"));
    }

    @Test
    void nonOrderCreatedEvent_isIgnored() throws Exception {
        PlatformEventMessage event = PlatformEventMessage.of(
                "OrderStatusChanged", "42", "{\"status\":\"DELIVERED\"}");

        consumer.onOrderEvent(objectMapper.writeValueAsString(event));

        verifyNoInteractions(statRepository);
    }

    @Test
    void malformedPayload_doesNotThrow() {
        consumer.onOrderEvent("not-json");

        verifyNoInteractions(statRepository);
    }
}