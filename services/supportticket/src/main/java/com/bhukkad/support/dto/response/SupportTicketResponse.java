package com.bhukkad.support.dto.response;

/**
 * Ticket view (mirrors the monolith's SupportTicketResponse field-for-field so
 * the existing client deserializes unchanged).
 */
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

    public SupportTicketResponse() {
    }

    public SupportTicketResponse(Long id, String ticketNumber, Long customerId, Long orderId,
                                 String category, String subject, String description,
                                 String status, String priority, String resolutionNotes,
                                 String createdAt, String updatedAt) {
        this.id = id;
        this.ticketNumber = ticketNumber;
        this.customerId = customerId;
        this.orderId = orderId;
        this.category = category;
        this.subject = subject;
        this.description = description;
        this.status = status;
        this.priority = priority;
        this.resolutionNotes = resolutionNotes;
        this.createdAt = createdAt;
        this.updatedAt = updatedAt;
    }

    public static Builder builder() {
        return new Builder();
    }

    public Long getId() {
        return id;
    }

    public String getTicketNumber() {
        return ticketNumber;
    }

    public Long getCustomerId() {
        return customerId;
    }

    public Long getOrderId() {
        return orderId;
    }

    public String getCategory() {
        return category;
    }

    public String getSubject() {
        return subject;
    }

    public String getDescription() {
        return description;
    }

    public String getStatus() {
        return status;
    }

    public String getPriority() {
        return priority;
    }

    public String getResolutionNotes() {
        return resolutionNotes;
    }

    public String getCreatedAt() {
        return createdAt;
    }

    public String getUpdatedAt() {
        return updatedAt;
    }

    public static class Builder {
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

        public Builder id(Long id) { this.id = id; return this; }
        public Builder ticketNumber(String ticketNumber) { this.ticketNumber = ticketNumber; return this; }
        public Builder customerId(Long customerId) { this.customerId = customerId; return this; }
        public Builder orderId(Long orderId) { this.orderId = orderId; return this; }
        public Builder category(String category) { this.category = category; return this; }
        public Builder subject(String subject) { this.subject = subject; return this; }
        public Builder description(String description) { this.description = description; return this; }
        public Builder status(String status) { this.status = status; return this; }
        public Builder priority(String priority) { this.priority = priority; return this; }
        public Builder resolutionNotes(String resolutionNotes) { this.resolutionNotes = resolutionNotes; return this; }
        public Builder createdAt(String createdAt) { this.createdAt = createdAt; return this; }
        public Builder updatedAt(String updatedAt) { this.updatedAt = updatedAt; return this; }

        public SupportTicketResponse build() {
            return new SupportTicketResponse(id, ticketNumber, customerId, orderId, category,
                    subject, description, status, priority, resolutionNotes, createdAt, updatedAt);
        }
    }
}