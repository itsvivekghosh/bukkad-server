package com.bhukkad.dto.response;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class SupportTicketResponse {
    private Long id;
    private String ticketNumber;
    private Long customerId;
    private Long orderId;
    private String category;
    private String subject;
    private String description;
    private String status;
    private String priority;
    private String resolutionNotes;
    private String createdAt;
    private String updatedAt;
}
