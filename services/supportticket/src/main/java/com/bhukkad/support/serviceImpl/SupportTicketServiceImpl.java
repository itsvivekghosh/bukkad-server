package com.bhukkad.support.serviceImpl;

import com.bhukkad.common.error.BusinessException;
import com.bhukkad.common.error.ResourceNotFoundException;
import com.bhukkad.common.scan.AllowFullScan;
import com.bhukkad.support.dto.request.SupportTicketRequest;
import com.bhukkad.support.dto.response.SupportTicketResponse;
import com.bhukkad.support.entity.SupportTicket;
import com.bhukkad.support.repository.SupportTicketRepository;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.util.List;
import java.util.Locale;

/**
 * Manages customer support tickets including creation and admin status updates.
 *
 * <p>The caller (monolith) resolves the authenticated customer and verifies
 * order ownership before proxying; this service owns the ticket lifecycle.
 * Order references are denormalized plain columns.</p>
 */
@Service
public class SupportTicketServiceImpl {

    private final SupportTicketRepository supportTicketRepository;

    public SupportTicketServiceImpl(SupportTicketRepository supportTicketRepository) {
        this.supportTicketRepository = supportTicketRepository;
    }

    /**
     * Creates a new support ticket for the given customer.
     */
    @Transactional
    public SupportTicketResponse create(Long customerId, SupportTicketRequest request) {
        if (customerId == null) {
            throw new BusinessException("Customer id is required");
        }
        SupportTicket ticket = new SupportTicket();
        ticket.setTicketNumber(generateTicketNumber());
        ticket.setCustomerId(customerId);
        ticket.setOrderId(request.getOrderId());
        ticket.setCategory(request.getCategory());
        ticket.setSubject(request.getSubject());
        ticket.setDescription(request.getDescription());
        ticket.setPriority(resolvePriority(request.getPriority()));
        ticket.setStatus(SupportTicket.TicketStatus.OPEN);

        return toResponse(supportTicketRepository.save(ticket));
    }

    /**
     * Lists all support tickets for a customer, newest first.
     */
    @Transactional(readOnly = true)
    public List<SupportTicketResponse> listCustomerTickets(Long customerId) {
        if (customerId == null) {
            throw new BusinessException("Customer id is required");
        }
        return supportTicketRepository.findByCustomerIdOrderByCreatedAtDesc(customerId).stream()
                .map(this::toResponse)
                .toList();
    }

    /**
     * Lists all support tickets for admin review, newest first.
     */
    @Transactional(readOnly = true)
    @AllowFullScan(reason = "G-6 KNOWN DEBT: whole-table admin ticket listing sorted by createdAt — V-04 pagination migration pending (tracked with the supportticket service-extraction batch); do not copy this pattern")
    public List<SupportTicketResponse> listAllForAdmin() {
        return supportTicketRepository.findAll(Sort.by(Sort.Direction.DESC, "createdAt")).stream()
                .map(this::toResponse)
                .toList();
    }

    /**
     * Updates ticket status and optional resolution notes (admin operation).
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
                .customerId(ticket.getCustomerId())
                .orderId(ticket.getOrderId())
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