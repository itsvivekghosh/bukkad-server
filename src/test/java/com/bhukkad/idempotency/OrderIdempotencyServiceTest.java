package com.bhukkad.idempotency;

import com.bhukkad.dto.response.OrderResponse;
import com.bhukkad.exception.BusinessException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;

import java.time.LocalDateTime;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class OrderIdempotencyServiceTest {

    @Mock
    private IdempotencyRecordRepository idempotencyRecordRepository;

    @Mock
    private IdempotencyService idempotencyService;

    private ObjectMapper objectMapper;

    @InjectMocks
    private OrderIdempotencyService orderIdempotencyService;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper().registerModule(new JavaTimeModule());
        orderIdempotencyService = new OrderIdempotencyService(
                idempotencyRecordRepository, idempotencyService, objectMapper);
    }

    @Test
    void beginOrderCreate_duplicateInProgress_throws() {
        IdempotencyRecord record = new IdempotencyRecord();
        record.setStatus(IdempotencyRecord.IdempotencyStatus.IN_PROGRESS);
        when(idempotencyRecordRepository.findByScopeAndIdempotencyKey(
                IdempotencyRecord.IdempotencyScope.ORDER_CREATE, "key-1"))
                .thenReturn(Optional.of(record));

        assertThrows(BusinessException.class,
                () -> orderIdempotencyService.beginOrderCreate("key-1", 99L));
    }

    @Test
    void beginOrderCreate_raceOnInsert_throws() {
        when(idempotencyRecordRepository.findByScopeAndIdempotencyKey(
                IdempotencyRecord.IdempotencyScope.ORDER_CREATE, "key-1"))
                .thenReturn(Optional.empty());
        when(idempotencyRecordRepository.save(any(IdempotencyRecord.class)))
                .thenThrow(new DataIntegrityViolationException("duplicate"));

        assertThrows(BusinessException.class,
                () -> orderIdempotencyService.beginOrderCreate("key-1", 99L));
    }

    @Test
    void findCompletedResponse_readsFromDatabaseWhenRedisMisses() throws Exception {
        OrderResponse response = OrderResponse.builder().id(1L).orderNumber("ORD-1").build();
        IdempotencyRecord record = new IdempotencyRecord();
        record.setStatus(IdempotencyRecord.IdempotencyStatus.COMPLETED);
        record.setResponsePayload(objectMapper.writeValueAsString(response));
        when(idempotencyService.getOrderResult("key-1", OrderResponse.class)).thenReturn(Optional.empty());
        when(idempotencyRecordRepository.findByScopeAndIdempotencyKey(
                IdempotencyRecord.IdempotencyScope.ORDER_CREATE, "key-1"))
                .thenReturn(Optional.of(record));

        Optional<OrderResponse> result = orderIdempotencyService.findCompletedResponse("key-1");

        assertEquals(1L, result.orElseThrow().getId());
    }

    @Test
    void completeOrderCreate_persistsCompletedRecord() throws Exception {
        IdempotencyRecord record = new IdempotencyRecord();
        record.setStatus(IdempotencyRecord.IdempotencyStatus.IN_PROGRESS);
        record.setExpiresAt(LocalDateTime.now().plusHours(1));
        when(idempotencyRecordRepository.findByScopeAndIdempotencyKey(
                IdempotencyRecord.IdempotencyScope.ORDER_CREATE, "key-1"))
                .thenReturn(Optional.of(record));

        OrderResponse response = OrderResponse.builder().id(5L).orderNumber("ORD-5").build();
        orderIdempotencyService.completeOrderCreate("key-1", response);

        assertEquals(IdempotencyRecord.IdempotencyStatus.COMPLETED, record.getStatus());
        verify(idempotencyRecordRepository).save(record);
        verify(idempotencyService).storeOrderResult(any(), any(), any());
    }

    @Test
    void failOrderCreate_marksRecordFailed() {
        IdempotencyRecord record = new IdempotencyRecord();
        record.setStatus(IdempotencyRecord.IdempotencyStatus.IN_PROGRESS);
        when(idempotencyRecordRepository.findByScopeAndIdempotencyKey(
                IdempotencyRecord.IdempotencyScope.ORDER_CREATE, "key-1"))
                .thenReturn(Optional.of(record));

        orderIdempotencyService.failOrderCreate("key-1");

        assertEquals(IdempotencyRecord.IdempotencyStatus.FAILED, record.getStatus());
        verify(idempotencyRecordRepository).save(record);
        verify(idempotencyService, never()).storeOrderResult(any(), any(), any());
    }
}
