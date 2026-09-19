package com.bhukkad.social.domain.service.impl;

import com.bhukkad.common.error.DuplicateRequestException;
import com.bhukkad.common.idempotency.IdempotencyRecord;
import com.bhukkad.common.idempotency.IdempotencyRecord.IdempotencyScope;
import com.bhukkad.common.idempotency.IdempotencyRecord.IdempotencyStatus;
import com.bhukkad.common.idempotency.IdempotencyRecordRepository;
import com.bhukkad.social.api.dto.request.OrderFromPostRequest;
import com.bhukkad.order.api.dto.response.OrderResponse;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import org.mockito.ArgumentCaptor;

/**
 * Unit tests for {@link OrderFromPostIdempotencyService}.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class OrderFromPostIdempotencyServiceTest {

    @Mock
    private IdempotencyRecordRepository idempotencyRepository;

    @Mock
    private ObjectMapper objectMapper;

    @InjectMocks
    private OrderFromPostIdempotencyService orderFromPostIdempotencyService;

    private Long testCustomerId = 10L;
    private Long testPostId = 1L;
    private String testIdempotencyKey = "test-key-123";
    private OrderFromPostRequest testRequest;
    private OrderResponse testOrderResponse;

    @BeforeEach
    void setUp() {
        testRequest = new OrderFromPostRequest(List.of(
                new OrderFromPostRequest.OrderItemRequest(200L, "Burger", new BigDecimal("12.99"), 2)
        ));

        testOrderResponse = new OrderResponse(
                1000L, 10L, 100L, "CONFIRMED", new BigDecimal("25.98"), List.of()
        );

        // Stub ObjectMapper for JSON serialization/deserialization
        try {
            // Use the actual SHA-256 hash of the deterministic JSON string
            String expectedHash = "3e50d3008ccdfad65e268ad9302e3ecdb38d09355abf9a42cb3db542cc2ceb1e";
            
            // Return a deterministic JSON string so hashOf produces a stable hash
            when(objectMapper.writeValueAsString(any())).thenAnswer(inv -> {
                Object arg = inv.getArgument(0);
                return "{\"customerId\":" + testCustomerId + ",\"postId\":" + testPostId + ",\"items\":[{\"menuItemId\":200,\"quantity\":2}]}";
            });
            when(objectMapper.readValue(anyString(), eq(OrderFromPostIdempotencyService.StoredEnvelope.class)))
                    .thenReturn(new OrderFromPostIdempotencyService.StoredEnvelope(expectedHash, 200, testOrderResponse));
            when(objectMapper.createObjectNode()).thenReturn(mock(ObjectNode.class));
            
            // Stub JsonNode for readTree - needs to support path().asText() and hasNonNull()
            JsonNode mockJsonNode = mock(JsonNode.class);
            JsonNode mockPathNode = mock(JsonNode.class);
            JsonNode mockBodyNode = mock(JsonNode.class);
            when(mockPathNode.asText(any())).thenReturn(expectedHash);
            when(mockJsonNode.path("requestHash")).thenReturn(mockPathNode);
            when(mockJsonNode.hasNonNull("body")).thenReturn(true);
            when(mockJsonNode.get("body")).thenReturn(mockBodyNode);
            doReturn(mockJsonNode).when(objectMapper).readTree(anyString());
        } catch (Exception e) {
            // Ignore JSON processing exceptions in test setup
        }
    }

    @Test
    void claim_freshKey_returnsFreshClaim() {
        // Arrange
        when(idempotencyRepository.insertIfAbsent(
                eq(testIdempotencyKey),
                eq(IdempotencyScope.ORDER_CREATE.name()),
                eq(testCustomerId),
                eq(IdempotencyStatus.IN_PROGRESS.name()),
                anyString(),
                any(LocalDateTime.class)
        )).thenReturn(1);

        // Act
        OrderFromPostIdempotencyService.Claim claim = orderFromPostIdempotencyService.claim(
                testCustomerId, testPostId, testIdempotencyKey, testRequest);

        // Assert
        assertTrue(claim.fresh());
        assertEquals(0, claim.httpStatus());
        assertNull(claim.replay());
        verify(idempotencyRepository).insertIfAbsent(
                eq(testIdempotencyKey),
                eq(IdempotencyScope.ORDER_CREATE.name()),
                eq(testCustomerId),
                eq(IdempotencyStatus.IN_PROGRESS.name()),
                anyString(),
                any(LocalDateTime.class)
        );
    }

    @Test
    void claim_replay_sameKeySameRequest_returnsCachedResponse() {
        // Arrange
        // First call would insert, second call finds existing record
        when(idempotencyRepository.insertIfAbsent(
                eq(testIdempotencyKey),
                eq(IdempotencyScope.ORDER_CREATE.name()),
                eq(testCustomerId),
                eq(IdempotencyStatus.IN_PROGRESS.name()),
                anyString(),
                any(LocalDateTime.class)
        )).thenReturn(0);  // Already exists

        IdempotencyRecord existingRecord = new IdempotencyRecord();
        existingRecord.setId(1L);
        existingRecord.setIdempotencyKey(testIdempotencyKey);
        existingRecord.setScope(IdempotencyScope.ORDER_CREATE);
        existingRecord.setOwnerId(testCustomerId);
        existingRecord.setStatus(IdempotencyStatus.COMPLETED);
        String expectedHash = "3e50d3008ccdfad65e268ad9302e3ecdb38d09355abf9a42cb3db542cc2ceb1e";
        existingRecord.setResponsePayload("{\"requestHash\":\"" + expectedHash + "\",\"httpStatus\":200,\"body\":{\"orderId\":1000,\"customerId\":10,\"restaurantId\":100,\"status\":\"CONFIRMED\",\"totalAmount\":\"25.98\",\"items\":[]}}");
        existingRecord.setExpiresAt(LocalDateTime.now().plus(Duration.ofHours(24)));

        when(idempotencyRepository.findByScopeAndIdempotencyKey(
                eq(IdempotencyScope.ORDER_CREATE),
                eq(testIdempotencyKey)
        )).thenReturn(java.util.Optional.of(existingRecord));

        // Act
        OrderFromPostIdempotencyService.Claim claim = orderFromPostIdempotencyService.claim(
                testCustomerId, testPostId, testIdempotencyKey, testRequest);

        // Assert
        assertFalse(claim.fresh());
        assertEquals(200, claim.httpStatus());
        assertEquals(testOrderResponse, claim.replay());
        verify(idempotencyRepository).findByScopeAndIdempotencyKey(
                eq(IdempotencyScope.ORDER_CREATE),
                eq(testIdempotencyKey)
        );
    }

    @Test
    void claim_replay_sameKeyDifferentRequest_throwsDuplicateRequestException() {
        // Arrange
        when(idempotencyRepository.insertIfAbsent(
                eq(testIdempotencyKey),
                eq(IdempotencyScope.ORDER_CREATE.name()),
                eq(testCustomerId),
                eq(IdempotencyStatus.IN_PROGRESS.name()),
                anyString(),
                any(LocalDateTime.class)
        )).thenReturn(0);  // Already exists

        IdempotencyRecord existingRecord = new IdempotencyRecord();
        existingRecord.setId(1L);
        existingRecord.setIdempotencyKey(testIdempotencyKey);
        existingRecord.setScope(IdempotencyScope.ORDER_CREATE);
        existingRecord.setOwnerId(testCustomerId);
        existingRecord.setStatus(IdempotencyStatus.COMPLETED);
        existingRecord.setResponsePayload("{\"requestHash\":\"differentHash\",\"httpStatus\":200,\"body\":{\"orderId\":1000,\"customerId\":10,\"restaurantId\":100,\"status\":\"CONFIRMED\",\"totalAmount\":\"25.98\",\"items\":[]}}");
        existingRecord.setExpiresAt(LocalDateTime.now().plus(Duration.ofHours(24)));

        when(idempotencyRepository.findByScopeAndIdempotencyKey(
                eq(IdempotencyScope.ORDER_CREATE),
                eq(testIdempotencyKey)
        )).thenReturn(java.util.Optional.of(existingRecord));

        // Override the readTree mock to return the hash from the responsePayload ("differentHash")
        JsonNode differentHashNode = mock(JsonNode.class);
        JsonNode differentPathNode = mock(JsonNode.class);
        when(differentPathNode.asText(any())).thenReturn("differentHash");
        when(differentHashNode.path("requestHash")).thenReturn(differentPathNode);
        try {
            doReturn(differentHashNode).when(objectMapper).readTree(anyString());
        } catch (Exception e) {
            // Ignore JSON processing exceptions in test setup
        }

        // Act & Assert
        assertThrows(
                DuplicateRequestException.class,
                () -> orderFromPostIdempotencyService.claim(
                        testCustomerId, testPostId, testIdempotencyKey, testRequest)
        );

        verify(idempotencyRepository).findByScopeAndIdempotencyKey(
                eq(IdempotencyScope.ORDER_CREATE),
                eq(testIdempotencyKey)
        );
    }

    @Test
    void claim_inProgressSameKey_throwsDuplicateRequestException() {
        // Arrange
        when(idempotencyRepository.insertIfAbsent(
                eq(testIdempotencyKey),
                eq(IdempotencyScope.ORDER_CREATE.name()),
                eq(testCustomerId),
                eq(IdempotencyStatus.IN_PROGRESS.name()),
                anyString(),
                any(LocalDateTime.class)
        )).thenReturn(0);  // Already exists

        IdempotencyRecord existingRecord = new IdempotencyRecord();
        existingRecord.setId(1L);
        existingRecord.setIdempotencyKey(testIdempotencyKey);
        existingRecord.setScope(IdempotencyScope.ORDER_CREATE);
        existingRecord.setOwnerId(999L);  // Different customer
        existingRecord.setStatus(IdempotencyStatus.IN_PROGRESS);
        existingRecord.setExpiresAt(LocalDateTime.now().plus(Duration.ofHours(24)));

        when(idempotencyRepository.findByScopeAndIdempotencyKey(
                eq(IdempotencyScope.ORDER_CREATE),
                eq(testIdempotencyKey)
        )).thenReturn(java.util.Optional.of(existingRecord));

        // Act & Assert
        assertThrows(
                DuplicateRequestException.class,
                () -> orderFromPostIdempotencyService.claim(
                        testCustomerId, testPostId, testIdempotencyKey, testRequest)
        );
    }

    @Test
    void complete_persistsResponsePayload() {
        // Arrange
        OrderFromPostIdempotencyService.Claim claim = OrderFromPostIdempotencyService.Claim.ofFresh();
        
        // Mock the existing record so requireOwnedClaim finds it
        IdempotencyRecord existingRecord = new IdempotencyRecord();
        existingRecord.setId(1L);
        existingRecord.setIdempotencyKey(testIdempotencyKey);
        existingRecord.setScope(IdempotencyScope.ORDER_CREATE);
        existingRecord.setOwnerId(testCustomerId);
        existingRecord.setStatus(IdempotencyStatus.IN_PROGRESS);
        when(idempotencyRepository.findByScopeAndIdempotencyKey(
                eq(IdempotencyScope.ORDER_CREATE), eq(testIdempotencyKey)
        )).thenReturn(java.util.Optional.of(existingRecord));

        // Act
        orderFromPostIdempotencyService.complete(
                testCustomerId, testPostId, testIdempotencyKey, testRequest,
                200, testOrderResponse);

        // Assert
        verify(idempotencyRepository).saveAndFlush(any(IdempotencyRecord.class));
        // Verify the saved record has the expected state
        ArgumentCaptor<IdempotencyRecord> captor = ArgumentCaptor.forClass(IdempotencyRecord.class);
        verify(idempotencyRepository).saveAndFlush(captor.capture());
        IdempotencyRecord saved = captor.getValue();
        assertEquals(testIdempotencyKey, saved.getIdempotencyKey());
        assertEquals(IdempotencyStatus.COMPLETED, saved.getStatus());
    }

    @Test
    void markFailed_persistsFailedStatus() {
        // Arrange
        OrderFromPostIdempotencyService.Claim claim = OrderFromPostIdempotencyService.Claim.ofFresh();
        
        // Mock the existing record so requireOwnedClaim finds it
        IdempotencyRecord existingRecord = new IdempotencyRecord();
        existingRecord.setId(1L);
        existingRecord.setIdempotencyKey(testIdempotencyKey);
        existingRecord.setScope(IdempotencyScope.ORDER_CREATE);
        existingRecord.setOwnerId(testCustomerId);
        existingRecord.setStatus(IdempotencyStatus.IN_PROGRESS);
        when(idempotencyRepository.findByScopeAndIdempotencyKey(
                eq(IdempotencyScope.ORDER_CREATE), eq(testIdempotencyKey)
        )).thenReturn(java.util.Optional.of(existingRecord));

        // Act
        orderFromPostIdempotencyService.markFailed(
                testCustomerId, testPostId, testIdempotencyKey);

        // Assert
        verify(idempotencyRepository).saveAndFlush(any(IdempotencyRecord.class));
        // Verify the saved record has the expected state
        ArgumentCaptor<IdempotencyRecord> captor = ArgumentCaptor.forClass(IdempotencyRecord.class);
        verify(idempotencyRepository).saveAndFlush(captor.capture());
        IdempotencyRecord saved = captor.getValue();
        assertEquals(testIdempotencyKey, saved.getIdempotencyKey());
        assertEquals(IdempotencyStatus.FAILED, saved.getStatus());
    }
}
