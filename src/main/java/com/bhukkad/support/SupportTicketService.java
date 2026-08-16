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
import com.bhukkad.support.SupportTicketService;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.util.List;
import java.util.Locale;

/**
 * Manages customer support tickets including creation and admin status updates.
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class SupportTicketService {

    private final SupportTicketRepository supportTicketRepository;
    private final CustomerRepository customerRepository;
    private final OrderRepository orderRepository;
    private final SecurityUtils securityUtils;

    /**
     * Creates a new support ticket for the authenticated customer.
     *
     * @param request ticket details
     * @return created ticket
     */
    @Transactional
    public SupportTicketResponse create(SupportTicketRequest request) {
        Long customerId = securityUtils.getCurrentUserId();
        Customer customer = customerRepository.findById(customerId)
                .orElseThrow(() -> new ResourceNotFoundException("Customer not found"));

        Order order = null;
        if (request.getOrderId() != null) {
            order = orderRepository.findById(request.getOrderId())
                    .orElseThrow(() -> new ResourceNotFoundException("Order not found"));
            if (!order.getCustomer().getId().equals(customerId)) {
                throw new BusinessException("Order does not belong to the current customer");
            }
        }

        SupportTicket ticket = new SupportTicket();
        ticket.setTicketNumber(generateTicketNumber());
        ticket.setCustomer(customer);
        ticket.setOrder(order);
        ticket.setCategory(request.getCategory());
        ticket.setSubject(request.getSubject());
        ticket.setDescription(request.getDescription());
        ticket.setPriority(resolvePriority(request.getPriority()));
        ticket.setStatus(SupportTicket.TicketStatus.OPEN);

        return toResponse(supportTicketRepository.save(ticket));
    }

    /**
     * Lists all support tickets for the authenticated customer, newest first.
     *
     * @return customer tickets
     */
    public List<SupportTicketResponse> listCustomerTickets() {
        Long customerId = securityUtils.getCurrentUserId();
        return supportTicketRepository.findByCustomerIdOrderByCreatedAtDesc(customerId).stream()
                .map(this::toResponse)
                .toList();
    }

    /**
     * Lists all support tickets for admin review, newest first.
     */
    public List<SupportTicketResponse> listAllForAdmin() {
        return supportTicketRepository.findAll(Sort.by(Sort.Direction.DESC, "createdAt")).stream()
                .map(this::toResponse)
                .toList();
    }

    /**
     * Updates ticket status and optional resolution notes (admin operation).
     *
     * @param ticketId        ticket identifier
     * @param status          new status name
     * @param resolutionNotes optional resolution notes
     * @return updated ticket
     */
    @Transactional
    public SupportTicketResponse adminUpdateStatus(Long ticketId, String status, String resolutionNotes) {
        SupportTicket ticket = supportTicketRepository.findById(ticketId)
                .orElseThrow(() -> new ResourceNotFoundException("Support ticket not found"));

        try {
            ticket.setStatus(SupportTicket.TicketStatus.valueOf(status.toUpperCase(Locale.ROOT)));
        } catch (IllegalArgumentException ex) {
            throw new BusinessException("Invalid ticket status: " + status);
        }

        if (StringUtils.hasText(resolutionNotes)) {
            ticket.setResolutionNotes(resolutionNotes);
        }

        return toResponse(supportTicketRepository.save(ticket));
    }

    private String generateTicketNumber() {
        return "TKT-" + System.currentTimeMillis();
    }

    private SupportTicket.TicketPriority resolvePriority(String priority) {
        if (!StringUtils.hasText(priority)) {
            return SupportTicket.TicketPriority.MEDIUM;
        }
        try {
            return SupportTicket.TicketPriority.valueOf(priority.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ex) {
            throw new BusinessException("Invalid ticket priority: " + priority);
        }
    }

    private SupportTicketResponse toResponse(SupportTicket ticket) {
        return SupportTicketResponse.builder()
                .id(ticket.getId())
                .ticketNumber(ticket.getTicketNumber())
                .customerId(ticket.getCustomer().getId())
                .orderId(ticket.getOrder() != null ? ticket.getOrder().getId() : null)
                .category(ticket.getCategory())
                .subject(ticket.getSubject())
                .description(ticket.getDescription())
                .status(ticket.getStatus().name())
                .priority(ticket.getPriority().name())
                .resolutionNotes(ticket.getResolutionNotes())
                .createdAt(ticket.getCreatedAt() != null ? ticket.getCreatedAt().toString() : null)
                .updatedAt(ticket.getUpdatedAt() != null ? ticket.getUpdatedAt().toString() : null)
                .build();
    }
}
