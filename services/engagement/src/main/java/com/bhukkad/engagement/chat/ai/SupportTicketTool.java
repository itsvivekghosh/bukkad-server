package com.bhukkad.engagement.chat.ai;

import java.util.Map;

/**
 * Tool interface for creating support tickets (human handoff).
 */
public interface SupportTicketTool {

    /**
     * Creates a support ticket with full conversation context.
     *
     * @param customerId      the customer ID
     * @param orderId         optional order ID related to the issue
     * @param subject         ticket subject
     * @param description     detailed description including conversation context
     * @param category        ticket category
     * @param priority        ticket priority
     * @param conversationContext map of conversation metadata
     * @return created ticket info
     */
    TicketResult createTicket(Long customerId,
                              Long orderId,
                              String subject,
                              String description,
                              String category,
                              String priority,
                              Map<String, Object> conversationContext);

    /**
     * Ticket creation result.
     */
    record TicketResult(
            boolean success,
            String ticketId,
            String ticketNumber,
            String status,
            String message
    ) {}
}