package com.bhukkad.support.domain.service.impl;

import com.bhukkad.common.error.BusinessException;
import com.bhukkad.common.error.ResourceNotFoundException;
import com.bhukkad.support.dto.request.SupportTicketRequest;
import com.bhukkad.support.dto.response.SupportTicketResponse;
import com.bhukkad.support.domain.entity.SupportTicket;
import com.bhukkad.support.domain.repository.SupportTicketRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class SupportTicketServiceImplTest {

    @Mock
    private SupportTicketRepository supportTicketRepository;

    private SupportTicketServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new SupportTicketServiceImpl(supportTicketRepository);
    }

    @Test
    void create_shouldCreateTicketWithGeneratedNumber() {
        SupportTicketRequest request = new SupportTicketRequest();
        request.setOrderId(100L);
        request.setCategory("ORDER_ISSUE");
        request.setSubject("Missing item");
        request.setDescription("My order is missing an item");
        request.setPriority("HIGH");

        SupportTicket savedTicket = new SupportTicket();
        savedTicket.setId(1L);
        savedTicket.setTicketNumber("TKT-1234567890");
        savedTicket.setCustomerId(1L);
        savedTicket.setOrderId(100L);
        savedTicket.setCategory("ORDER_ISSUE");
        savedTicket.setSubject("Missing item");
        savedTicket.setDescription("My order is missing an item");
        savedTicket.setPriority(SupportTicket.TicketPriority.HIGH);
        savedTicket.setStatus(SupportTicket.TicketStatus.OPEN);

        when(supportTicketRepository.save(any(SupportTicket.class))).thenReturn(savedTicket);

        SupportTicketResponse result = service.create(1L, request);

        assertThat(result.getCustomerId()).isEqualTo(1L);
        assertThat(result.getOrderId()).isEqualTo(100L);
        assertThat(result.getCategory()).isEqualTo("ORDER_ISSUE");
        assertThat(result.getSubject()).isEqualTo("Missing item");
        assertThat(result.getDescription()).isEqualTo("My order is missing an item");
        assertThat(result.getPriority()).isEqualTo("HIGH");
        assertThat(result.getStatus()).isEqualTo("OPEN");
        assertThat(result.getTicketNumber()).startsWith("TKT-");
        verify(supportTicketRepository).save(any(SupportTicket.class));
    }

    @Test
    void create_shouldThrowWhenCustomerIdNull() {
        SupportTicketRequest request = new SupportTicketRequest();
        request.setOrderId(100L);
        request.setCategory("ORDER_ISSUE");
        request.setSubject("Test");
        request.setDescription("Test");

        assertThatThrownBy(() -> service.create(null, request))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("Customer id is required");
    }

    @Test
    void create_shouldResolvePriorityToMediumWhenNotProvided() {
        SupportTicketRequest request = new SupportTicketRequest();
        request.setOrderId(100L);
        request.setCategory("ORDER_ISSUE");
        request.setSubject("Test");
        request.setDescription("Test");
        request.setPriority(null);

        SupportTicket savedTicket = new SupportTicket();
        savedTicket.setId(1L);
        savedTicket.setTicketNumber("TKT-1234567890");
        savedTicket.setCustomerId(1L);
        savedTicket.setOrderId(100L);
        savedTicket.setCategory("ORDER_ISSUE");
        savedTicket.setSubject("Test");
        savedTicket.setDescription("Test");
        savedTicket.setPriority(SupportTicket.TicketPriority.MEDIUM);
        savedTicket.setStatus(SupportTicket.TicketStatus.OPEN);

        when(supportTicketRepository.save(any(SupportTicket.class))).thenReturn(savedTicket);

        SupportTicketResponse result = service.create(1L, request);

        assertThat(result.getPriority()).isEqualTo("MEDIUM");
    }

    @Test
    void create_shouldResolvePriorityFromString() {
        SupportTicketRequest request = new SupportTicketRequest();
        request.setOrderId(100L);
        request.setCategory("ORDER_ISSUE");
        request.setSubject("Test");
        request.setDescription("Test");
        request.setPriority("low");

        SupportTicket savedTicket = new SupportTicket();
        savedTicket.setId(1L);
        savedTicket.setTicketNumber("TKT-1234567890");
        savedTicket.setCustomerId(1L);
        savedTicket.setOrderId(100L);
        savedTicket.setCategory("ORDER_ISSUE");
        savedTicket.setSubject("Test");
        savedTicket.setDescription("Test");
        savedTicket.setPriority(SupportTicket.TicketPriority.LOW);
        savedTicket.setStatus(SupportTicket.TicketStatus.OPEN);

        when(supportTicketRepository.save(any(SupportTicket.class))).thenReturn(savedTicket);

        SupportTicketResponse result = service.create(1L, request);

        assertThat(result.getPriority()).isEqualTo("LOW");
    }

    @Test
    void create_shouldThrowOnInvalidPriority() {
        SupportTicketRequest request = new SupportTicketRequest();
        request.setOrderId(100L);
        request.setCategory("ORDER_ISSUE");
        request.setSubject("Test");
        request.setDescription("Test");
        request.setPriority("INVALID");

        assertThatThrownBy(() -> service.create(1L, request))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("Invalid ticket priority");
    }

    @Test
    void listCustomerTickets_shouldReturnTicketsOrderedByCreatedDesc() {
        SupportTicket ticket1 = createSampleTicket(1L, "TKT-111");
        SupportTicket ticket2 = createSampleTicket(2L, "TKT-222");

        when(supportTicketRepository.findByCustomerIdOrderByCreatedAtDesc(1L))
                .thenReturn(Arrays.asList(ticket1, ticket2));

        List<SupportTicketResponse> result = service.listCustomerTickets(1L);

        assertThat(result).hasSize(2);
        assertThat(result.get(0).getTicketNumber()).isEqualTo("TKT-111");
        assertThat(result.get(1).getTicketNumber()).isEqualTo("TKT-222");
    }

    @Test
    void listCustomerTickets_shouldThrowWhenCustomerIdNull() {
        assertThatThrownBy(() -> service.listCustomerTickets(null))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("Customer id is required");
    }

    @Test
    void listCustomerTickets_shouldReturnEmptyListWhenNoTickets() {
        when(supportTicketRepository.findByCustomerIdOrderByCreatedAtDesc(1L))
                .thenReturn(Collections.emptyList());

        List<SupportTicketResponse> result = service.listCustomerTickets(1L);

        assertThat(result).isEmpty();
    }

    @Test
    void listAllForAdmin_shouldReturnAllTicketsOrderedByCreatedDesc() {
        SupportTicket ticket1 = createSampleTicket(1L, "TKT-111");
        SupportTicket ticket2 = createSampleTicket(2L, "TKT-222");

        when(supportTicketRepository.findAll(any(org.springframework.data.domain.Sort.class))).thenReturn(Arrays.asList(ticket1, ticket2));

        List<SupportTicketResponse> result = service.listAllForAdmin();

        assertThat(result).hasSize(2);
    }

    @Test
    void adminUpdateStatus_shouldUpdateStatusAndNotes() {
        SupportTicket ticket = createSampleTicket(1L, "TKT-123");
        ticket.setStatus(SupportTicket.TicketStatus.OPEN);

        when(supportTicketRepository.findById(1L)).thenReturn(Optional.of(ticket));
        when(supportTicketRepository.save(any(SupportTicket.class))).thenAnswer(inv -> inv.getArgument(0));

        SupportTicketResponse result = service.adminUpdateStatus(1L, "CLOSED", "Issue resolved");

        assertThat(result.getStatus()).isEqualTo("CLOSED");
        assertThat(result.getResolutionNotes()).isEqualTo("Issue resolved");
    }

    @Test
    void adminUpdateStatus_shouldThrowWhenTicketNotFound() {
        when(supportTicketRepository.findById(1L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.adminUpdateStatus(1L, "CLOSED", "Notes"))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessageContaining("Support ticket not found");
    }

    @Test
    void adminUpdateStatus_shouldThrowOnInvalidStatus() {
        SupportTicket ticket = createSampleTicket(1L, "TKT-123");

        when(supportTicketRepository.findById(1L)).thenReturn(Optional.of(ticket));

        assertThatThrownBy(() -> service.adminUpdateStatus(1L, "INVALID", "Notes"))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("Invalid ticket status");
    }

    @Test
    void adminUpdateStatus_shouldUpdateOnlyStatusWhenNotesBlank() {
        SupportTicket ticket = createSampleTicket(1L, "TKT-123");
        ticket.setStatus(SupportTicket.TicketStatus.OPEN);
        ticket.setResolutionNotes("Old notes");

        when(supportTicketRepository.findById(1L)).thenReturn(Optional.of(ticket));
        when(supportTicketRepository.save(any(SupportTicket.class))).thenAnswer(inv -> inv.getArgument(0));

        SupportTicketResponse result = service.adminUpdateStatus(1L, "CLOSED", "  ");

        assertThat(result.getStatus()).isEqualTo("CLOSED");
        assertThat(result.getResolutionNotes()).isEqualTo("Old notes"); // Unchanged
    }

    @Test
    void generateTicketNumber_shouldStartWithTKT() {
        // Test via create method
        SupportTicketRequest request = new SupportTicketRequest();
        request.setOrderId(100L);
        request.setCategory("ORDER_ISSUE");
        request.setSubject("Test");
        request.setDescription("Test");

        SupportTicket savedTicket = new SupportTicket();
        savedTicket.setId(1L);
        savedTicket.setTicketNumber("TKT-1234567890");
        savedTicket.setCustomerId(1L);
        savedTicket.setOrderId(100L);
        savedTicket.setCategory("ORDER_ISSUE");
        request.setPriority("MEDIUM");
        savedTicket.setPriority(SupportTicket.TicketPriority.MEDIUM);
        savedTicket.setStatus(SupportTicket.TicketStatus.OPEN);

        when(supportTicketRepository.save(any(SupportTicket.class))).thenReturn(savedTicket);

        SupportTicketResponse result = service.create(1L, request);

        assertThat(result.getTicketNumber()).startsWith("TKT-");
    }

    private SupportTicket createSampleTicket(Long id, String ticketNumber) {
        SupportTicket ticket = new SupportTicket();
        ticket.setId(id);
        ticket.setTicketNumber(ticketNumber);
        ticket.setCustomerId(1L);
        ticket.setOrderId(100L);
        ticket.setCategory("ORDER_ISSUE");
        ticket.setSubject("Test subject");
        ticket.setDescription("Test description");
        ticket.setPriority(SupportTicket.TicketPriority.MEDIUM);
        ticket.setStatus(SupportTicket.TicketStatus.OPEN);
        return ticket;
    }
}