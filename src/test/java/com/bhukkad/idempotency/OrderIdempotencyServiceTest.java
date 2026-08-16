package com.bhukkad.idempotency;

import com.bhukkad.dto.response.OrderResponse;
import com.bhukkad.exception.BusinessException;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;

import java.time.LocalDateTime;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class OrderIdempotencyServiceTest {

    @Mock
    private IdempotencyRecordRepository idempotencyRecordRepository;
    @Mock
    private IdempotencyService idempotencyService;
    @Mock
    private ObjectMapper objectMapper;

    private OrderIdempotencyService orderIdempotencyService;

    @BeforeEach
    void setUp() {
        orderIdempotencyService = new OrderIdempotencyService(idempotencyRecordRepository, idempotencyService, objectMapper);
    }

    @Test
    void testFindCompletedResponse_WithCachedResponse_ReturnsCached() {
        OrderResponse mockedResponse = new OrderResponse();
        when(idempotencyService.getOrderResult("idem-key", OrderResponse.class))
                .thenReturn(Optional.of(mockedResponse));

        Optional<OrderResponse> result = orderIdempotencyService.findCompletedResponse("idem-key");

        assertTrue(result.isPresent());
        assertEquals(mockedResponse, result.get());
        verify(idempotencyRecordRepository, never()).findByScopeAndIdempotencyKey(any(), anyString());
    }

    @Test
    void testFindCompletedResponse_WithDBRecord_ReturnsResponse() throws JsonProcessingException {
        IdempotencyRecord record = new IdempotencyRecord();
        record.setStatus(IdempotencyRecord.IdempotencyStatus.COMPLETED);
        record.setResponsePayload("{\"id\":123}");

        when(idempotencyService.getOrderResult("idem-key", OrderResponse.class))
                .thenReturn(Optional.empty());
        when(idempotencyRecordRepository.findByScopeAndIdempotencyKey(
                IdempotencyRecord.IdempotencyScope.ORDER_CREATE, "idem-key"))
                .thenReturn(Optional.of(record));

        OrderResponse mockedResponse = new OrderResponse();
        mockedResponse.setId(123L);
        when(objectMapper.readValue("{\"id\":123}", OrderResponse.class))
                .thenReturn(mockedResponse);

        Optional<OrderResponse> result = orderIdempotencyService.findCompletedResponse("idem-key");

        assertTrue(result.isPresent());
        assertEquals(123L, result.get().getId());
    }

    @Test
    void testFindCompletedResponse_WithEmptyKey_ReturnsEmpty() {
        Optional<OrderResponse> result = orderIdempotencyService.findCompletedResponse("");
        assertTrue(result.isEmpty());
    }

    @Test
    void testFindCompletedResponse_WithNullKey_ReturnsEmpty() {
        Optional<OrderResponse> result = orderIdempotencyService.findCompletedResponse(null);
        assertTrue(result.isEmpty());
    }

    @Test
    void testFindCompletedResponse_WithNonCompletedRecord_ReturnsEmpty() {
        IdempotencyRecord record = new IdempotencyRecord();
        record.setStatus(IdempotencyRecord.IdempotencyStatus.IN_PROGRESS);

        when(idempotencyService.getOrderResult("idem-key", OrderResponse.class))
                .thenReturn(Optional.empty());
        when(idempotencyRecordRepository.findByScopeAndIdempotencyKey(
                IdempotencyRecord.IdempotencyScope.ORDER_CREATE, "idem-key"))
                .thenReturn(Optional.of(record));

        Optional<OrderResponse> result = orderIdempotencyService.findCompletedResponse("idem-key");

        assertTrue(result.isEmpty());
    }

    @Test
    void testBeginOrderCreate_NewRecord_CreatesInProgress() {
        when(idempotencyRecordRepository.findByScopeAndIdempotencyKey(
                IdempotencyRecord.IdempotencyScope.ORDER_CREATE, "new-key"))
                .thenReturn(Optional.empty());

        orderIdempotencyService.beginOrderCreate("new-key", 1L);

        ArgumentCaptor<IdempotencyRecord> captor = ArgumentCaptor.forClass(IdempotencyRecord.class);
        verify(idempotencyRecordRepository).save(captor.capture());

        IdempotencyRecord saved = captor.getValue();
        assertEquals("new-key", saved.getIdempotencyKey());
        assertEquals(IdempotencyRecord.IdempotencyScope.ORDER_CREATE, saved.getScope());
        assertEquals(1L, saved.getOwnerId());
        assertEquals(IdempotencyRecord.IdempotencyStatus.IN_PROGRESS, saved.getStatus());
        assertNotNull(saved.getExpiresAt());
    }

    @Test
    void testBeginOrderCreate_WithDuplicateKey_ThrowsException() {
        when(idempotencyRecordRepository.findByScopeAndIdempotencyKey(
                IdempotencyRecord.IdempotencyScope.ORDER_CREATE, "duplicate"))
                .thenReturn(Optional.empty());

        doThrow(new DataIntegrityViolationException("Duplicate"))
                .when(idempotencyRecordRepository).save(any(IdempotencyRecord.class));

        BusinessException ex = assertThrows(BusinessException.class, () ->
                orderIdempotencyService.beginOrderCreate("duplicate", 1L));

        assertTrue(ex.getMessage().contains("Duplicate order request"));
    }

    @Test
    void testBeginOrderCreate_WithInProgressRecord_ThrowsException() {
        IdempotencyRecord existing = new IdempotencyRecord();
        existing.setStatus(IdempotencyRecord.IdempotencyStatus.IN_PROGRESS);

        when(idempotencyRecordRepository.findByScopeAndIdempotencyKey(
                IdempotencyRecord.IdempotencyScope.ORDER_CREATE, "in-progress"))
                .thenReturn(Optional.of(existing));

        BusinessException ex = assertThrows(BusinessException.class, () ->
                orderIdempotencyService.beginOrderCreate("in-progress", 1L));

        assertTrue(ex.getMessage().contains("already being processed"));
    }

    @Test
    void testBeginOrderCreate_WithCompletedRecord_ReturnsSilently() {
        IdempotencyRecord existing = new IdempotencyRecord();
        existing.setStatus(IdempotencyRecord.IdempotencyStatus.COMPLETED);

        when(idempotencyRecordRepository.findByScopeAndIdempotencyKey(
                IdempotencyRecord.IdempotencyScope.ORDER_CREATE, "completed"))
                .thenReturn(Optional.of(existing));

        // Should not throw
        assertDoesNotThrow(() ->
                orderIdempotencyService.beginOrderCreate("completed", 1L));

        verify(idempotencyRecordRepository, never()).save(any());
    }

    @Test
    void testBeginOrderCreate_WithFailedRecord_TransitionsToInProgress() {
        IdempotencyRecord existing = new IdempotencyRecord();
        existing.setStatus(IdempotencyRecord.IdempotencyStatus.FAILED);

        when(idempotencyRecordRepository.findByScopeAndIdempotencyKey(
                IdempotencyRecord.IdempotencyScope.ORDER_CREATE, "failed"))
                .thenReturn(Optional.of(existing));

        orderIdempotencyService.beginOrderCreate("failed", 1L);

        assertEquals(IdempotencyRecord.IdempotencyStatus.IN_PROGRESS, existing.getStatus());
        verify(idempotencyRecordRepository).save(existing);
    }

    @Test
    void testCompleteOrderCreate_Succeeds() throws JsonProcessingException {
        IdempotencyRecord record = new IdempotencyRecord();
        record.setStatus(IdempotencyRecord.IdempotencyStatus.IN_PROGRESS);

        when(idempotencyRecordRepository.findByScopeAndIdempotencyKey(
                IdempotencyRecord.IdempotencyScope.ORDER_CREATE, "idem-key"))
                .thenReturn(Optional.of(record));
        when(objectMapper.writeValueAsString(any(OrderResponse.class)))
                .thenReturn("{\"status\":\"success\"}");

        OrderResponse response = new OrderResponse();
        response.setId(123L);

        orderIdempotencyService.completeOrderCreate("idem-key", response);

        assertEquals(IdempotencyRecord.IdempotencyStatus.COMPLETED, record.getStatus());
        assertEquals("{\"status\":\"success\"}", record.getResponsePayload());
        verify(idempotencyService).storeOrderResult("idem-key", response, java.time.Duration.ofHours(24));
    }

    @Test
    void testCompleteOrderCreate_WithNullKey_DoesNothing() {
        orderIdempotencyService.completeOrderCreate(null, new OrderResponse());
        verify(idempotencyRecordRepository, never()).findByScopeAndIdempotencyKey(any(), anyString());
    }

    @Test
    void testFailOrderCreate_WithExistingRecord_SetsFailed() {
        IdempotencyRecord record = new IdempotencyRecord();
        record.setStatus(IdempotencyRecord.IdempotencyStatus.IN_PROGRESS);

        when(idempotencyRecordRepository.findByScopeAndIdempotencyKey(
                IdempotencyRecord.IdempotencyScope.ORDER_CREATE, "failed-key"))
                .thenReturn(Optional.of(record));

        orderIdempotencyService.failOrderCreate("failed-key");

        assertEquals(IdempotencyRecord.IdempotencyStatus.FAILED, record.getStatus());
        verify(idempotencyRecordRepository).save(record);
    }

    @Test
    void testFailOrderCreate_WithNonExistentRecord_DoesNothing() {
        when(idempotencyRecordRepository.findByScopeAndIdempotencyKey(
                IdempotencyRecord.IdempotencyScope.ORDER_CREATE, "non-existent"))
                .thenReturn(Optional.empty());

        assertDoesNotThrow(() -> orderIdempotencyService.failOrderCreate("non-existent"));
        verify(idempotencyRecordRepository, never()).save(any());
    }

    @Test
    void testFailOrderCreate_WithNullKey_DoesNothing() {
        orderIdempotencyService.failOrderCreate(null);
        verify(idempotencyRecordRepository, never()).findByScopeAndIdempotencyKey(any(), anyString());
    }

    @Test
    void testBeginOrderCreate_WithNullKey_DoesNothing() {
        assertDoesNotThrow(() -> orderIdempotencyService.beginOrderCreate(null, 1L));
        verify(idempotencyRecordRepository, never()).save(any());
    }

    @Test
    void testFindCompletedResponse_SerializationFailure_ReturnsEmpty() throws JsonProcessingException {
        IdempotencyRecord record = new IdempotencyRecord();
        record.setStatus(IdempotencyRecord.IdempotencyStatus.COMPLETED);
        record.setResponsePayload("invalid-json");

        when(idempotencyService.getOrderResult("idem-key", OrderResponse.class))
                .thenReturn(Optional.empty());
        when(idempotencyRecordRepository.findByScopeAndIdempotencyKey(
                IdempotencyRecord.IdempotencyScope.ORDER_CREATE, "idem-key"))
                .thenReturn(Optional.of(record));
        when(objectMapper.readValue("invalid-json", OrderResponse.class))
                .thenThrow(new JsonProcessingException("Parse error") {});

        Optional<OrderResponse> result = orderIdempotencyService.findCompletedResponse("idem-key");

        assertTrue(result.isEmpty());
    }
}
