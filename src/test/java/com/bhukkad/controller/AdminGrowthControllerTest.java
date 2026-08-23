package com.bhukkad.controller;

import com.bhukkad.dto.response.ApiResponse;
import com.bhukkad.dto.response.SupportTicketResponse;
import com.bhukkad.support.SupportTicketService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AdminGrowthControllerTest {

    @Mock
    private SupportTicketService supportTicketService;

    @InjectMocks
    private AdminGrowthController controller;

    @Test
    void listSupportTickets_returnsAllTickets() {
        List<SupportTicketResponse> tickets = List.of(SupportTicketResponse.builder().build());
        when(supportTicketService.listAllForAdmin()).thenReturn(tickets);

        ResponseEntity<ApiResponse<List<SupportTicketResponse>>> response = controller.listSupportTickets();

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertSame(tickets, response.getBody().getData());
    }

    @Test
    void updateTicketStatus_returnsUpdatedTicket() {
        SupportTicketResponse ticket = SupportTicketResponse.builder().build();
        when(supportTicketService.adminUpdateStatus(3L, "RESOLVED", "Fixed")).thenReturn(ticket);

        ResponseEntity<ApiResponse<SupportTicketResponse>> response =
                controller.updateTicketStatus(3L, "RESOLVED", "Fixed");

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals("Ticket updated", response.getBody().getMessage());
        assertSame(ticket, response.getBody().getData());
        verify(supportTicketService).adminUpdateStatus(3L, "RESOLVED", "Fixed");
    }
}