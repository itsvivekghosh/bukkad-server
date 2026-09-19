package com.bhukkad.social.api.controller;

import com.bhukkad.common.error.ResourceNotFoundException;
import com.bhukkad.common.error.BusinessException;
import com.bhukkad.common.security.TokenPrincipal;
import com.bhukkad.social.api.dto.request.OrderFromPostRequest;
import com.bhukkad.order.api.dto.response.OrderResponse;
import com.bhukkad.social.api.dto.response.PostSummary;
import com.bhukkad.social.domain.service.SocialPostService;
import com.bhukkad.social.domain.service.impl.OrderFromPostIdempotencyService;
import com.bhukkad.social.infrastructure.client.OrderServiceClient;
import com.bhukkad.social.infrastructure.client.RestaurantClient;
import com.bhukkad.social.infrastructure.messaging.OrderFromPostEventPublisher;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.List;

import com.bhukkad.social.observability.PerformanceMetrics;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for OrderFromPostController.
 */
@ExtendWith(MockitoExtension.class)
class OrderFromPostControllerTest {

    @Mock
    private SocialPostService socialPostService;

    @Mock
    private RestaurantClient restaurantClient;

    @Mock
    private OrderServiceClient orderServiceClient;

    @Mock
    private OrderFromPostEventPublisher orderFromPostEventPublisher;

    @Mock
    private OrderFromPostIdempotencyService orderFromPostIdempotencyService;

    @Mock
    private PerformanceMetrics performanceMetrics;

    @InjectMocks
    private OrderFromPostController orderFromPostController;

    private PostSummary testPost;
    private OrderFromPostRequest testRequest;
    private OrderResponse testOrderResponse;
    private TokenPrincipal testPrincipal;

    @BeforeEach
    void setUp() {
        // Set up test data
        testPost = new PostSummary(
                1L,       // id
                100L,     // restaurantId
                "Test Restaurant",  // restaurantName
                10L,      // authorId
                "Test User",  // authorName
                "Check out this burger!",  // content
                new String[]{},  // mediaUrls
                "update",  // postType
                5,        // likeCount
                2,        // commentCount
                "active",  // status
                java.time.LocalDateTime.now()  // createdAt
        );

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
                List.of()  // items - simplified for test
        );

        testPrincipal = new TokenPrincipal(10L, "test@example.com", "USER");
    }

    @Test
    void createOrderFromPost_success() {
        // Arrange
        when(socialPostService.getPost(1L)).thenReturn(testPost);
        when(restaurantClient.getRestaurant(100L)).thenReturn(java.util.Map.of("id", 100L, "name", "Test Restaurant"));
        when(restaurantClient.getMenuItem(200L)).thenReturn(java.util.Map.of("id", 200L, "name", "Burger", "restaurantId", 100L));
        when(restaurantClient.getMenuItem(201L)).thenReturn(java.util.Map.of("id", 201L, "name", "Fries", "restaurantId", 100L));
        when(orderFromPostIdempotencyService.claim(10L, 1L, "test-key", testRequest)).thenReturn(
                new OrderFromPostIdempotencyService.Claim(true, 0, null));
        when(orderServiceClient.createOrderFromPost(any())).thenReturn(testOrderResponse);

        // Act
        var response = orderFromPostController.createOrderFromPost(
                1L,  // postId
                "test-key",  // idempotencyKey
                testRequest,
                testPrincipal
        );

        // Assert
        assertEquals(201, response.getStatusCodeValue());
        assertEquals(testOrderResponse, response.getBody());
        verify(socialPostService).getPost(1L);
        verify(restaurantClient).getRestaurant(100L);
        verify(restaurantClient).getMenuItem(200L);
        verify(restaurantClient).getMenuItem(201L);
        verify(orderFromPostIdempotencyService).claim(10L, 1L, "test-key", testRequest);
        verify(orderServiceClient).createOrderFromPost(any());
        verify(orderFromPostEventPublisher).publishOrderFromPostEvent(
                eq(1L), eq(10L), eq(100L), eq(1000L), eq(new BigDecimal("29.97")), eq(testRequest.items()));
        verify(orderFromPostIdempotencyService).complete(10L, 1L, "test-key", testRequest, 200, testOrderResponse);
    }

    @Test
    void createOrderFromPost_postNotFound() {
        // Arrange
        when(socialPostService.getPost(999L)).thenThrow(new ResourceNotFoundException("Post not found"));

        // Act & Assert
        ResourceNotFoundException exception = assertThrows(
                ResourceNotFoundException.class,
                () -> orderFromPostController.createOrderFromPost(
                        999L,  // postId
                        "test-key",  // idempotencyKey
                        testRequest,
                        testPrincipal
                )
        );

        assertTrue(exception.getMessage().contains("Post not found"));
        verify(socialPostService).getPost(999L);
        verifyNoInteractions(restaurantClient);
        verifyNoInteractions(orderServiceClient);
        verifyNoInteractions(orderFromPostIdempotencyService);
        verifyNoInteractions(orderFromPostEventPublisher);
    }

    @Test
    void createOrderFromPost_restaurantNotFound() {
        // Arrange
        when(socialPostService.getPost(1L)).thenReturn(testPost);
        when(restaurantClient.getRestaurant(100L)).thenReturn(java.util.Map.of());  // Empty map = not found

        // Act & Assert
        assertThrows(
                BusinessException.class,
                () -> orderFromPostController.createOrderFromPost(
                        1L,  // postId
                        "test-key",  // idempotencyKey
                        testRequest,
                        testPrincipal
                )
        );

        verify(socialPostService).getPost(1L);
        verify(restaurantClient).getRestaurant(100L);
        verifyNoInteractions(orderServiceClient);
        verifyNoInteractions(orderFromPostIdempotencyService);
        verifyNoInteractions(orderFromPostEventPublisher);
    }

    @Test
    void createOrderFromPost_menuItemNotFound() {
        // Arrange
        when(socialPostService.getPost(1L)).thenReturn(testPost);
        when(restaurantClient.getRestaurant(100L)).thenReturn(java.util.Map.of("id", 100L, "name", "Test Restaurant"));
        when(restaurantClient.getMenuItem(200L)).thenReturn(java.util.Map.of());  // Empty map = not found
        when(restaurantClient.getMenuItem(201L)).thenReturn(java.util.Map.of("id", 201L, "name", "Fries", "restaurantId", 100L));

        // Act & Assert
        assertThrows(
                BusinessException.class,
                () -> orderFromPostController.createOrderFromPost(
                        1L,  // postId
                        "test-key",  // idempotencyKey
                        testRequest,
                        testPrincipal
                )
        );

        verify(socialPostService).getPost(1L);
        verify(restaurantClient).getRestaurant(100L);
        verify(restaurantClient).getMenuItem(200L);
        verifyNoInteractions(orderServiceClient);
        verifyNoInteractions(orderFromPostIdempotencyService);
        verifyNoInteractions(orderFromPostEventPublisher);
    }

    @Test
    void createOrderFromPost_idempotencyReplay() {
        // Arrange
        when(socialPostService.getPost(1L)).thenReturn(testPost);
        when(restaurantClient.getRestaurant(100L)).thenReturn(java.util.Map.of("id", 100L, "name", "Test Restaurant"));
        when(restaurantClient.getMenuItem(200L)).thenReturn(java.util.Map.of("id", 200L, "name", "Burger", "restaurantId", 100L));
        when(restaurantClient.getMenuItem(201L)).thenReturn(java.util.Map.of("id", 201L, "name", "Fries", "restaurantId", 100L));
        when(orderFromPostIdempotencyService.claim(10L, 1L, "test-key", testRequest)).thenReturn(
                new OrderFromPostIdempotencyService.Claim(false, 200, testOrderResponse));

        // Act
        var response = orderFromPostController.createOrderFromPost(
                1L,  // postId
                "test-key",  // idempotencyKey
                testRequest,
                testPrincipal
        );

        // Assert
        assertEquals(200, response.getStatusCodeValue());
        assertEquals(testOrderResponse, response.getBody());
        verify(socialPostService).getPost(1L);
        verify(restaurantClient).getRestaurant(100L);
        verify(restaurantClient).getMenuItem(200L);
        verify(restaurantClient).getMenuItem(201L);
        verify(orderFromPostIdempotencyService).claim(10L, 1L, "test-key", testRequest);
        verify(orderFromPostIdempotencyService, never()).complete(any(), any(), any(), any(), anyInt(), any());
        verify(orderServiceClient, never()).createOrderFromPost(any());
        verify(orderFromPostEventPublisher, never()).publishOrderFromPostEvent(any(), any(), any(), any(), any(), any());
    }
}
