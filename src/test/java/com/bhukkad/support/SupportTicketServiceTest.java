package com.bhukkad.support;

import com.bhukkad.dto.request.SupportTicketRequest;
import com.bhukkad.dto.response.SupportTicketResponse;
import com.bhukkad.entity.Customer;
import com.bhukkad.entity.Order;
import com.bhukkad.entity.SupportTicket;
import com.bhukkad.exception.BusinessException;
import com.bhukkad.exception.ResourceNotFoundException;
import com.bhukkad.repository.CustomerRepository;
import com.bhukkad.repository.OrderRepository;
import com.bhukkad.repository.SupportTicketRepository;
import com.bhukkad.security.SecurityUtils;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Sort;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SupportTicketServiceTest {

    private static final Long CUSTOMER_ID = 42L;
    private static final Long ORDER_ID = 99L;

    @Mock
    private SupportTicketRepository supportTicketRepository;
    @Mock
    private CustomerRepository customerRepository;
    @Mock
    private OrderRepository orderRepository;
    @Mock
    private SecurityUtils securityUtils;

    @InjectMocks
    private SupportTicketService service;

    private Customer customer() {
        Customer customer = new Customer();
        customer.setId(CUSTOMER_ID);
        return customer;
    }

    private Order orderFor(Long orderId, Customer customer) {
        Order order = new Order();
        order.setId(orderId);
        order.setCustomer(customer);
        return order;
    }

    private SupportTicketRequest request() {
        SupportTicketRequest request = new SupportTicketRequest();
        request.setOrderId(ORDER_ID);
        request.setCategory("DELIVERY");
        request.setSubject("Food arrived cold");
        request.setDescription("Please investigate");
        request.setPriority("HIGH");
        return request;
    }

    @Test
    void create_withOrder_mapsAllFields() {
        Customer customer = customer();
        Order order = orderFor(ORDER_ID, customer);
        SupportTicket saved = new SupportTicket();
        saved.setId(1L);
        saved.setTicketNumber("TKT-123");
        saved.setCustomer(customer);
        saved.setOrder(order);
        saved.setCategory("DELIVERY");
        saved.setSubject("Food arrived cold");
        saved.setDescription("Please investigate");
        saved.setPriority(SupportTicket.TicketPriority.HIGH);
        saved.setStatus(SupportTicket.TicketStatus.OPEN);
        saved.setCreatedAt(LocalDateTime.of(2026, 8, 22, 10, 0));
        saved.setUpdatedAt(LocalDateTime.of(2026, 8, 22, 11, 0));

        when(securityUtils.getCurrentUserId()).thenReturn(CUSTOMER_ID);
        when(customerRepository.findById(CUSTOMER_ID)).thenReturn(Optional.of(customer));
        when(orderRepository.findById(ORDER_ID)).thenReturn(Optional.of(order));
        when(supportTicketRepository.save(any(SupportTicket.class))).thenReturn(saved);

        SupportTicketResponse response = service.create(request());

        assertEquals(1L, response.getId());
        assertEquals("TKT-123", response.getTicketNumber());
        assertEquals(CUSTOMER_ID, response.getCustomerId());
        assertEquals(ORDER_ID, response.getOrderId());
        assertEquals("DELIVERY", response.getCategory());
        assertEquals("Food arrived cold", response.getSubject());
        assertEquals("Please investigate", response.getDescription());
        assertEquals("OPEN", response.getStatus());
        assertEquals("HIGH", response.getPriority());
        assertEquals("2026-08-22T10:00", response.getCreatedAt());
        assertEquals("2026-08-22T11:00", response.getUpdatedAt());

        ArgumentCaptor<SupportTicket> captor = ArgumentCaptor.forClass(SupportTicket.class);
        verify(supportTicketRepository).save(captor.capture());
        SupportTicket persisted = captor.getValue();
        assertNotNull(persisted.getTicketNumber());
        assertTrue(persisted.getTicketNumber().startsWith("TKT-"));
        assertEquals(customer, persisted.getCustomer());
        assertEquals(order, persisted.getOrder());
        assertEquals(SupportTicket.TicketStatus.OPEN, persisted.getStatus());
    }

    @Test
    void create_withoutOrder_mapsNullOrder() {
        Customer customer = customer();
        SupportTicket saved = new SupportTicket();
        saved.setId(1L);
        saved.setTicketNumber("TKT-1");
        saved.setCustomer(customer);
        saved.setOrder(null);
        saved.setCategory("OTHER");
        saved.setSubject("Feedback");
        saved.setStatus(SupportTicket.TicketStatus.OPEN);
        saved.setPriority(SupportTicket.TicketPriority.MEDIUM);

        SupportTicketRequest request = request();
        request.setOrderId(null);

        when(securityUtils.getCurrentUserId()).thenReturn(CUSTOMER_ID);
        when(customerRepository.findById(CUSTOMER_ID)).thenReturn(Optional.of(customer));
        when(supportTicketRepository.save(any(SupportTicket.class))).thenReturn(saved);

        SupportTicketResponse response = service.create(request);

        assertNull(response.getOrderId());
        assertEquals(CUSTOMER_ID, response.getCustomerId());
        verify(orderRepository, never()).findById(any());
    }

    @Test
    void create_blankPriority_defaultsToMedium() {
        Customer customer = customer();
        SupportTicket saved = new SupportTicket();
        saved.setId(1L);
        saved.setTicketNumber("TKT-2");
        saved.setCustomer(customer);
        saved.setCategory("OTHER");
        saved.setSubject("S");
        saved.setStatus(SupportTicket.TicketStatus.OPEN);
        saved.setPriority(SupportTicket.TicketPriority.MEDIUM);

        SupportTicketRequest request = request();
        request.setOrderId(null);
        request.setPriority("  ");

        when(securityUtils.getCurrentUserId()).thenReturn(CUSTOMER_ID);
        when(customerRepository.findById(CUSTOMER_ID)).thenReturn(Optional.of(customer));
        when(supportTicketRepository.save(any(SupportTicket.class))).thenReturn(saved);

        SupportTicketResponse response = service.create(request);

        assertEquals("MEDIUM", response.getPriority());
    }

    @Test
    void create_nullPriority_defaultsToMedium() {
        Customer customer = customer();
        SupportTicket saved = new SupportTicket();
        saved.setId(1L);
        saved.setTicketNumber("TKT-3");
        saved.setCustomer(customer);
        saved.setCategory("OTHER");
        saved.setSubject("S");
        saved.setStatus(SupportTicket.TicketStatus.OPEN);
        saved.setPriority(SupportTicket.TicketPriority.MEDIUM);

        SupportTicketRequest request = request();
        request.setOrderId(null);
        request.setPriority(null);

        when(securityUtils.getCurrentUserId()).thenReturn(CUSTOMER_ID);
        when(customerRepository.findById(CUSTOMER_ID)).thenReturn(Optional.of(customer));
        when(supportTicketRepository.save(any(SupportTicket.class))).thenReturn(saved);

        SupportTicketResponse response = service.create(request);

        assertEquals("MEDIUM", response.getPriority());
    }

    @Test
    void create_lowercasePriority_normalized() {
        Customer customer = customer();
        SupportTicket saved = new SupportTicket();
        saved.setId(1L);
        saved.setTicketNumber("TKT-4");
        saved.setCustomer(customer);
        saved.setCategory("OTHER");
        saved.setSubject("S");
        saved.setStatus(SupportTicket.TicketStatus.OPEN);
        saved.setPriority(SupportTicket.TicketPriority.URGENT);

        SupportTicketRequest request = request();
        request.setOrderId(null);
        request.setPriority("urgent");

        when(securityUtils.getCurrentUserId()).thenReturn(CUSTOMER_ID);
        when(customerRepository.findById(CUSTOMER_ID)).thenReturn(Optional.of(customer));
        when(supportTicketRepository.save(any(SupportTicket.class))).thenReturn(saved);

        SupportTicketResponse response = service.create(request);

        assertEquals("URGENT", response.getPriority());
    }

    @Test
    void create_customerNotFound_throws() {
        when(securityUtils.getCurrentUserId()).thenReturn(CUSTOMER_ID);
        when(customerRepository.findById(CUSTOMER_ID)).thenReturn(Optional.empty());

        ResourceNotFoundException ex = assertThrows(ResourceNotFoundException.class,
                () -> service.create(request()));

        assertEquals("Customer not found", ex.getMessage());
        verify(supportTicketRepository, never()).save(any());
    }

    @Test
    void create_orderNotFound_throws() {
        Customer customer = customer();
        when(securityUtils.getCurrentUserId()).thenReturn(CUSTOMER_ID);
        when(customerRepository.findById(CUSTOMER_ID)).thenReturn(Optional.of(customer));
        when(orderRepository.findById(ORDER_ID)).thenReturn(Optional.empty());

        ResourceNotFoundException ex = assertThrows(ResourceNotFoundException.class,
                () -> service.create(request()));

        assertEquals("Order not found", ex.getMessage());
    }

    @Test
    void create_orderBelongsToAnotherCustomer_throws() {
        Customer customer = customer();
        Customer other = new Customer();
        other.setId(7L);
        Order order = orderFor(ORDER_ID, other);

        when(securityUtils.getCurrentUserId()).thenReturn(CUSTOMER_ID);
        when(customerRepository.findById(CUSTOMER_ID)).thenReturn(Optional.of(customer));
        when(orderRepository.findById(ORDER_ID)).thenReturn(Optional.of(order));

        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.create(request()));

        assertEquals("Order does not belong to the current customer", ex.getMessage());
        verify(supportTicketRepository, never()).save(any());
    }

    @Test
    void create_invalidPriority_throws() {
        Customer customer = customer();
        SupportTicketRequest request = request();
        request.setOrderId(null);
        request.setPriority("EXTREME");

        when(securityUtils.getCurrentUserId()).thenReturn(CUSTOMER_ID);
        when(customerRepository.findById(CUSTOMER_ID)).thenReturn(Optional.of(customer));

        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.create(request));

        assertEquals("Invalid ticket priority: EXTREME", ex.getMessage());
        verify(supportTicketRepository, never()).save(any());
    }

    @Test
    void listCustomerTickets_mapsAll() {
        SupportTicket ticket = new SupportTicket();
        ticket.setId(1L);
        ticket.setTicketNumber("TKT-1");
        ticket.setCustomer(customer());
        ticket.setCategory("DELIVERY");
        ticket.setSubject("Late");
        ticket.setDescription("d");
        ticket.setStatus(SupportTicket.TicketStatus.OPEN);
        ticket.setPriority(SupportTicket.TicketPriority.LOW);

        when(securityUtils.getCurrentUserId()).thenReturn(CUSTOMER_ID);
        when(supportTicketRepository.findByCustomerIdOrderByCreatedAtDesc(CUSTOMER_ID))
                .thenReturn(List.of(ticket));

        List<SupportTicketResponse> responses = service.listCustomerTickets();

        assertEquals(1, responses.size());
        assertEquals("TKT-1", responses.get(0).getTicketNumber());
        assertEquals(CUSTOMER_ID, responses.get(0).getCustomerId());
        assertNull(responses.get(0).getOrderId());
    }

    @Test
    void listCustomerTickets_emptyList() {
        when(securityUtils.getCurrentUserId()).thenReturn(CUSTOMER_ID);
        when(supportTicketRepository.findByCustomerIdOrderByCreatedAtDesc(CUSTOMER_ID))
                .thenReturn(List.of());

        assertTrue(service.listCustomerTickets().isEmpty());
    }

    @Test
    void listAllForAdmin_returnsNewestFirst() {
        SupportTicket ticket = new SupportTicket();
        ticket.setId(2L);
        ticket.setTicketNumber("TKT-2");
        ticket.setCustomer(customer());
        ticket.setCategory("PAYMENT");
        ticket.setSubject("Refund");
        ticket.setStatus(SupportTicket.TicketStatus.IN_PROGRESS);
        ticket.setPriority(SupportTicket.TicketPriority.HIGH);
        ticket.setResolutionNotes("looking into it");

        when(supportTicketRepository.findAll(any(Sort.class))).thenReturn(List.of(ticket));

        List<SupportTicketResponse> responses = service.listAllForAdmin();

        assertEquals(1, responses.size());
        assertEquals("IN_PROGRESS", responses.get(0).getStatus());
        assertEquals("looking into it", responses.get(0).getResolutionNotes());
    }

    @Test
    void adminUpdateStatus_validStatusAndNotes_updatesTicket() {
        SupportTicket ticket = new SupportTicket();
        ticket.setId(5L);
        ticket.setTicketNumber("TKT-5");
        ticket.setCustomer(customer());
        ticket.setCategory("DELIVERY");
        ticket.setSubject("Missing items");
        ticket.setStatus(SupportTicket.TicketStatus.OPEN);
        ticket.setPriority(SupportTicket.TicketPriority.MEDIUM);

        when(supportTicketRepository.findById(5L)).thenReturn(Optional.of(ticket));
        when(supportTicketRepository.save(any(SupportTicket.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        SupportTicketResponse response = service.adminUpdateStatus(5L, "RESOLVED", "Refund processed");

        assertEquals("RESOLVED", response.getStatus());
        assertEquals("Refund processed", response.getResolutionNotes());

        ArgumentCaptor<SupportTicket> captor = ArgumentCaptor.forClass(SupportTicket.class);
        verify(supportTicketRepository).save(captor.capture());
        assertEquals(SupportTicket.TicketStatus.RESOLVED, captor.getValue().getStatus());
        assertEquals("Refund processed", captor.getValue().getResolutionNotes());
    }

    @Test
    void adminUpdateStatus_lowercaseStatus_normalized() {
        SupportTicket ticket = new SupportTicket();
        ticket.setId(5L);
        ticket.setTicketNumber("TKT-5");
        ticket.setCustomer(customer());
        ticket.setCategory("DELIVERY");
        ticket.setSubject("Missing items");
        ticket.setStatus(SupportTicket.TicketStatus.OPEN);
        ticket.setPriority(SupportTicket.TicketPriority.MEDIUM);

        when(supportTicketRepository.findById(5L)).thenReturn(Optional.of(ticket));
        when(supportTicketRepository.save(any(SupportTicket.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        SupportTicketResponse response = service.adminUpdateStatus(5L, "closed", null);

        assertEquals("CLOSED", response.getStatus());
    }

    @Test
    void adminUpdateStatus_blankNotes_doNotOverwrite() {
        SupportTicket ticket = new SupportTicket();
        ticket.setId(5L);
        ticket.setTicketNumber("TKT-5");
        ticket.setCustomer(customer());
        ticket.setCategory("DELIVERY");
        ticket.setSubject("Missing items");
        ticket.setStatus(SupportTicket.TicketStatus.OPEN);
        ticket.setPriority(SupportTicket.TicketPriority.MEDIUM);
        ticket.setResolutionNotes("existing");

        when(supportTicketRepository.findById(5L)).thenReturn(Optional.of(ticket));
        when(supportTicketRepository.save(any(SupportTicket.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        SupportTicketResponse response = service.adminUpdateStatus(5L, "OPEN", "   ");

        assertEquals("existing", response.getResolutionNotes());
    }

    @Test
    void adminUpdateStatus_nullNotes_doNotOverwrite() {
        SupportTicket ticket = new SupportTicket();
        ticket.setId(5L);
        ticket.setTicketNumber("TKT-5");
        ticket.setCustomer(customer());
        ticket.setCategory("DELIVERY");
        ticket.setSubject("Missing items");
        ticket.setStatus(SupportTicket.TicketStatus.OPEN);
        ticket.setPriority(SupportTicket.TicketPriority.MEDIUM);
        ticket.setResolutionNotes("existing");

        when(supportTicketRepository.findById(5L)).thenReturn(Optional.of(ticket));
        when(supportTicketRepository.save(any(SupportTicket.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        SupportTicketResponse response = service.adminUpdateStatus(5L, "OPEN", null);

        assertEquals("existing", response.getResolutionNotes());
    }

    @Test
    void adminUpdateStatus_ticketNotFound_throws() {
        when(supportTicketRepository.findById(5L)).thenReturn(Optional.empty());

        ResourceNotFoundException ex = assertThrows(ResourceNotFoundException.class,
                () -> service.adminUpdateStatus(5L, "RESOLVED", null));

        assertEquals("Support ticket not found", ex.getMessage());
    }

    @Test
    void adminUpdateStatus_invalidStatus_throws() {
        SupportTicket ticket = new SupportTicket();
        ticket.setId(5L);
        ticket.setTicketNumber("TKT-5");
        ticket.setCustomer(customer());
        ticket.setCategory("DELIVERY");
        ticket.setSubject("Missing items");
        ticket.setStatus(SupportTicket.TicketStatus.OPEN);
        ticket.setPriority(SupportTicket.TicketPriority.MEDIUM);

        when(supportTicketRepository.findById(5L)).thenReturn(Optional.of(ticket));

        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.adminUpdateStatus(5L, "BOGUS", null));

        assertEquals("Invalid ticket status: BOGUS", ex.getMessage());
        verify(supportTicketRepository, never()).save(any());
    }

    @Test
    void adminUpdateStatus_nullStatus_throws() {
        SupportTicket ticket = new SupportTicket();
        ticket.setId(5L);
        ticket.setTicketNumber("TKT-5");
        ticket.setCustomer(customer());
        ticket.setCategory("DELIVERY");
        ticket.setSubject("Missing items");
        ticket.setStatus(SupportTicket.TicketStatus.OPEN);
        ticket.setPriority(SupportTicket.TicketPriority.MEDIUM);

        when(supportTicketRepository.findById(5L)).thenReturn(Optional.of(ticket));

        assertThrows(NullPointerException.class,
                () -> service.adminUpdateStatus(5L, null, null));
    }
}
