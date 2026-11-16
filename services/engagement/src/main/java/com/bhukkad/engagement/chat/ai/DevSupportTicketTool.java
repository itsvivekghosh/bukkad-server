package com.bhukkad.engagement.chat.ai;

import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Random;

/**
 * Development implementation of SupportTicketTool.
 * <p>
 * <b>DEV ONLY</b> — Creates local ticket references. In production, use {@link SupportTicketToolImpl}.
 */
@Component
@Profile({"dev", "test", "local"})
public class DevSupportTicketTool implements SupportTicketTool {

    private static final Random RANDOM = new Random();

    @Override
    public TicketResult createTicket(Long customerId,
                                     Long orderId,
                                     String subject,
                                     String description,
                                     String category,
                                     String priority,
                                     Map<String, Object> conversationContext) {

        if (customerId == null) {
            return new TicketResult(false, null, null, "REJECTED", "Customer ID required");
        }

        String ticketId = String.valueOf(10000 + RANDOM.nextInt(90000));
        String ticketNumber = "TKT-" + ticketId;

        return new TicketResult(
                true, ticketId, ticketNumber, "OPEN",
                "Support ticket created (dev mode). Subject: " + subject
        );
    }
}