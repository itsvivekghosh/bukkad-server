package com.bhukkad.social.infrastructure.messaging;

import com.bhukkad.common.event.PlatformEventMessage;
import com.bhukkad.common.outbox.OutboxClient;
import com.bhukkad.social.api.dto.request.OrderFromPostRequest;
import com.bhukkad.order.api.dto.response.OrderResponse;
import com.bhukkad.social.domain.service.PostOrderConversionService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for OrderFromPostEventPublisher.
 */
@ExtendWith(MockitoExtension.class)
class OrderFromPostEventPublisherTest {

    @Mock
    private OutboxClient outboxClient;

    @Mock
    private PostOrderConversionService postOrderConversionService;

    @InjectMocks
    private OrderFromPostEventPublisher orderFromPostEventPublisher;

    private Long testPostId = 1L;
    private Long testUserId = 10L;
    private Long testRestaurantId = 100L;
    private Long testOrderId = 1000L;
    private OrderFromPostRequest testRequest;
    private OrderResponse testOrderResponse;

    @BeforeEach
    void setUp() {
        testRequest = new OrderFromPostRequest(List.of(
                new OrderFromPostRequest.OrderItemRequest(200L, "Burger", new BigDecimal("12.99"), 2),
                new OrderFromPostRequest.OrderItemRequest(201L, "Fries", new BigDecimal("3.99"), 1)
        ));

        testOrderResponse = new OrderResponse(
                1000L,  // orderId
                10L,    // customerId
                100L,   // restaurantId
                "CONFIRMED",
                new BigDecimal("29.97"),  // totalAmount
                List.of()  // items
        );
    }

    @Test
    void publishOrderFromPostEvent_createsCorrectPayload_andCallsOutboxClient() {
        // Act
        orderFromPostEventPublisher.publishOrderFromPostEvent(
                testPostId, testUserId, testRestaurantId, testOrderId, testOrderResponse.totalAmount(), testRequest.items());

        // Assert
        // Verify that outboxClient.enqueue was called with correct parameters
        verify(outboxClient).enqueue(
                eq(OrderFromPostEventPublisher.TYPE_ORDER_FROM_POST),  // eventType
                eq(testOrderId),  // aggregateId (orderId)
                argThat(payload -> {
                    // Verify the payload contains the expected fields
                    return payload.contains("\"postId\":1") &&
                           payload.contains("\"userId\":10") &&
                           payload.contains("\"restaurantId\":100") &&
                           payload.contains("\"orderId\":1000") &&
                           payload.contains("\"items\":[{\"menuItemId\":200,\"quantity\":2},{\"menuItemId\":201,\"quantity\":1}]");
                })
        );

        // Verify the log message
        // Note: In a real test with a mock logger, we would verify this
        // For simplicity, we're just verifying the method was called correctly
    }

    @Test
    void publishOrderFromPostEvent_emptyItemsList() {
        // Arrange
        OrderFromPostRequest emptyRequest = new OrderFromPostRequest(List.of());

        // Act
        orderFromPostEventPublisher.publishOrderFromPostEvent(
                testPostId, testUserId, testRestaurantId, testOrderId, BigDecimal.ZERO, emptyRequest.items());

        // Assert
        verify(outboxClient).enqueue(
                eq(OrderFromPostEventPublisher.TYPE_ORDER_FROM_POST),
                eq(testOrderId),
                argThat(payload -> {
                    return payload.contains("\"postId\":1") &&
                           payload.contains("\"userId\":10") &&
                           payload.contains("\"restaurantId\":100") &&
                           payload.contains("\"orderId\":1000") &&
                           payload.contains("\"items\":[]");
                })
        );
    }
}