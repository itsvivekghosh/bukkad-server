package com.bhukkad.support.api;

import com.bhukkad.common.error.BusinessException;
import com.bhukkad.common.error.UnauthorizedException;
import com.bhukkad.common.security.TokenPrincipal;
import com.bhukkad.support.dto.request.SupportTicketRequest;
import com.bhukkad.support.dto.response.ApiResponse;
import com.bhukkad.support.dto.response.SupportTicketResponse;
import com.bhukkad.support.serviceImpl.SupportTicketServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.ResponseEntity;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class SupportTicketControllerTest {

    @Mock
    private SupportTicketServiceImpl supportTicketService;

    private SupportTicketController controller;

    @BeforeEach
    void setUp() {
        controller = new SupportTicketController(supportTicketService);
    }

    @Test
    void create_shouldCreateTicketFromValidRequest() {
        TokenPrincipal principal = new TokenPrincipal(1L, "test@example.com", "CUSTOMER");
        SupportTicketRequest request = new SupportTicketRequest();
        request.setOrderId(100L);
        request.setCategory("ORDER_ISSUE");
        request.setSubject("Missing item");
        request.setDescription("Order missing item");
        request.setPriority("HIGH");

        SupportTicketResponse expected = SupportTicketResponse.builder()
                .id(1L)
                .ticketNumber("TKT-123")
                .customerId(1L)
                .orderId(100L)
                .category("ORDER_ISSUE")
                .subject("Missing item")
                .description("Order missing item")
                .status("OPEN")
                .priority("HIGH")
                .build();

        when(supportTicketService.create(1L, request)).thenReturn(expected);

        ResponseEntity<ApiResponse<SupportTicketResponse>> response = controller.create(principal, request);

        assertThat(response.getStatusCode().is2xxSuccessful()).isTrue();
        assertThat(response.getBody().data()).isEqualTo(expected);
    }

    @Test
    void create_shouldThrowWhenPrincipalNull() {
        SupportTicketRequest request = new SupportTicketRequest();
        request.setOrderId(100L);
        request.setCategory("ORDER_ISSUE");
        request.setSubject("Test");
        request.setDescription("Test");

        assertThatThrownBy(() -> controller.create(null, request))
                .isInstanceOf(UnauthorizedException.class)
                .hasMessageContaining("Authenticated customer required");
    }

    @Test
    void create_shouldThrowWhenUserIdNull() {
        TokenPrincipal principal = new TokenPrincipal(null, "test@example.com", "CUSTOMER");
        SupportTicketRequest request = new SupportTicketRequest();
        request.setOrderId(100L);
        request.setCategory("ORDER_ISSUE");
        request.setSubject("Test");
        request.setDescription("Test");

        assertThatThrownBy(() -> controller.create(principal, request))
                .isInstanceOf(UnauthorizedException.class)
                .hasMessageContaining("Authenticated customer required");
    }

    @Test
    void listCustomerTickets_shouldReturnCustomerTickets() {
        TokenPrincipal principal = new TokenPrincipal(1L, "test@example.com", "CUSTOMER");
        SupportTicketResponse ticket = SupportTicketResponse.builder()
                .id(1L)
                .ticketNumber("TKT-123")
                .customerId(1L)
                .orderId(100L)
                .category("ORDER_ISSUE")
                .subject("Test")
                .description("Test")
                .status("OPEN")
                .priority("MEDIUM")
                .build();

        when(supportTicketService.listCustomerTickets(1L)).thenReturn(Collections.singletonList(ticket));

        ResponseEntity<ApiResponse<List<SupportTicketResponse>>> response = controller.listCustomerTickets(principal);

        assertThat(response.getStatusCode().is2xxSuccessful()).isTrue();
        assertThat(response.getBody().data()).hasSize(1);
        assertThat(response.getBody().data().get(0).getTicketNumber()).isEqualTo("TKT-123");
    }

    @Test
    void listCustomerTickets_shouldThrowWhenPrincipalNull() {
        assertThatThrownBy(() -> controller.listCustomerTickets(null))
                .isInstanceOf(UnauthorizedException.class)
                .hasMessageContaining("Authenticated customer required");
    }

    @Test
    void listAllForAdmin_shouldReturnAllTickets() {
        SupportTicketResponse ticket = SupportTicketResponse.builder()
                .id(1L)
                .ticketNumber("TKT-123")
                .customerId(1L)
                .orderId(100L)
                .category("ORDER_ISSUE")
                .subject("Test")
                .description("Test")
                .status("OPEN")
                .priority("MEDIUM")
                .build();

        when(supportTicketService.listAllForAdmin()).thenReturn(Arrays.asList(ticket));

        ResponseEntity<ApiResponse<List<SupportTicketResponse>>> response = controller.listAllForAdmin();

        assertThat(response.getStatusCode().is2xxSuccessful()).isTrue();
        assertThat(response.getBody().data()).hasSize(1);
    }

    @Test
    void adminUpdateStatus_shouldUpdateFromQueryParams() {
        SupportTicketResponse expected = SupportTicketResponse.builder()
                .id(1L)
                .ticketNumber("TKT-123")
                .customerId(1L)
                .orderId(100L)
                .category("ORDER_ISSUE")
                .subject("Test")
                .description("Test")
                .status("CLOSED")
                .priority("MEDIUM")
                .resolutionNotes("Resolved")
                .build();

        when(supportTicketService.adminUpdateStatus(1L, "CLOSED", "Resolved")).thenReturn(expected);

        ResponseEntity<ApiResponse<SupportTicketResponse>> response = controller.adminUpdateStatus(
                1L, "CLOSED", "Resolved", null);

        assertThat(response.getStatusCode().is2xxSuccessful()).isTrue();
        assertThat(response.getBody().data().getStatus()).isEqualTo("CLOSED");
        assertThat(response.getBody().data().getResolutionNotes()).isEqualTo("Resolved");
    }

    @Test
    void adminUpdateStatus_shouldUpdateFromBody() {
        SupportTicketResponse expected = SupportTicketResponse.builder()
                .id(1L)
                .ticketNumber("TKT-123")
                .status("CLOSED")
                .resolutionNotes("From body")
                .build();

        when(supportTicketService.adminUpdateStatus(1L, "CLOSED", "From body")).thenReturn(expected);

        Map<String, String> body = Map.of("status", "CLOSED", "resolutionNotes", "From body");

        ResponseEntity<ApiResponse<SupportTicketResponse>> response = controller.adminUpdateStatus(
                1L, null, null, body);

        assertThat(response.getStatusCode().is2xxSuccessful()).isTrue();
        assertThat(response.getBody().data().getStatus()).isEqualTo("CLOSED");
        assertThat(response.getBody().data().getResolutionNotes()).isEqualTo("From body");
    }

    @Test
    void adminUpdateStatus_shouldPreferQueryParamsOverBody() {
        SupportTicketResponse expected = SupportTicketResponse.builder()
                .id(1L)
                .status("CLOSED")
                .resolutionNotes("Query param")
                .build();

        when(supportTicketService.adminUpdateStatus(1L, "CLOSED", "Query param")).thenReturn(expected);

        Map<String, String> body = Map.of("status", "OPEN", "resolutionNotes", "Body notes");

        ResponseEntity<ApiResponse<SupportTicketResponse>> response = controller.adminUpdateStatus(
                1L, "CLOSED", "Query param", body);

        assertThat(response.getStatusCode().is2xxSuccessful()).isTrue();
        assertThat(response.getBody().data().getStatus()).isEqualTo("CLOSED");
        assertThat(response.getBody().data().getResolutionNotes()).isEqualTo("Query param");
    }

    @Test
    void adminUpdateStatus_shouldThrowWhenStatusMissing() {
        Map<String, String> body = Map.of("resolutionNotes", "Only notes");

        assertThatThrownBy(() -> controller.adminUpdateStatus(1L, null, null, body))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("status is required");
    }

    @Test
    void adminUpdateStatus_shouldThrowWhenStatusBlank() {
        assertThatThrownBy(() -> controller.adminUpdateStatus(1L, "  ", "Notes", null))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("status is required");
    }
}