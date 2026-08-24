package com.bhukkad.controller;

import com.bhukkad.dto.response.DeadLetterEventResponse;
import com.bhukkad.outbox.DeadLetterEvent;
import com.bhukkad.outbox.DeadLetterEventService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.ResponseEntity;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AdminDeadLetterControllerTest {

    @Mock
    private DeadLetterEventService deadLetterEventService;

    @InjectMocks
    private AdminDeadLetterController controller;

    private DeadLetterEvent event(Long id) {
        DeadLetterEvent e = new DeadLetterEvent();
        e.setId(id);
        e.setEventType("ORDER_CREATED");
        e.setAggregateType("ORDER");
        e.setAggregateId(id);
        e.setPayload("{}");
        e.setStatus(DeadLetterEvent.DlqStatus.PENDING);
        return e;
    }

    @Test void list_defaultLimit() {
        when(deadLetterEventService.listRecent(20)).thenReturn(List.of(event(1L)));
        ResponseEntity<com.bhukkad.dto.response.ApiResponse<List<DeadLetterEventResponse>>> resp =
                controller.list(20);
        assertEquals(1, resp.getBody().getData().size());
        assertEquals(1L, resp.getBody().getData().get(0).getId());
    }

    @Test void countPending_returnsCount() {
        when(deadLetterEventService.countPending()).thenReturn(7L);
        ResponseEntity<com.bhukkad.dto.response.ApiResponse<Long>> resp = controller.countPending();
        assertEquals(7L, resp.getBody().getData());
    }

    @Test void getById_returnsEvent() {
        when(deadLetterEventService.getById(3L)).thenReturn(event(3L));
        ResponseEntity<com.bhukkad.dto.response.ApiResponse<DeadLetterEventResponse>> resp =
                controller.getById(3L);
        assertEquals(3L, resp.getBody().getData().getId());
        assertEquals("ORDER_CREATED", resp.getBody().getData().getEventType());
    }

    @Test void requeue_returnsRequeuedEvent() {
        when(deadLetterEventService.requeueOne(5L)).thenReturn(event(5L));
        ResponseEntity<com.bhukkad.dto.response.ApiResponse<DeadLetterEventResponse>> resp =
                controller.requeue(5L);
        assertEquals(5L, resp.getBody().getData().getId());
        verify(deadLetterEventService).requeueOne(5L);
    }
}
