package com.bhukkad.support.dto.response;

public class DisputeResponse {
    private Long id;
    private Long orderId;
    private String orderNumber;
    private String type;
    private String status;
    private String customerEvidence;
    private String riderEvidence;
    private String restaurantEvidence;
    private String resolutionNotes;
    private String resolution;
    private Double refundAmount;
    private Long resolvedBy;
    private String resolvedAt;
    private String createdAt;

    public DisputeResponse() {
    }

    public DisputeResponse(Long id, Long orderId, String orderNumber, String type, String status, String customerEvidence, String riderEvidence, String restaurantEvidence, String resolutionNotes, String resolution, Double refundAmount, Long resolvedBy, String resolvedAt, String createdAt) {
        this.id = id;
        this.orderId = orderId;
        this.orderNumber = orderNumber;
        this.type = type;
        this.status = status;
        this.customerEvidence = customerEvidence;
        this.riderEvidence = riderEvidence;
        this.restaurantEvidence = restaurantEvidence;
        this.resolutionNotes = resolutionNotes;
        this.resolution = resolution;
        this.refundAmount = refundAmount;
        this.resolvedBy = resolvedBy;
        this.resolvedAt = resolvedAt;
        this.createdAt = createdAt;
    }

    public static Builder builder() {
        return new Builder();
    }

    public Long getId() {
        return this.id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public Long getOrderId() {
        return this.orderId;
    }

    public void setOrderId(Long orderId) {
        this.orderId = orderId;
    }

    public String getOrderNumber() {
        return this.orderNumber;
    }

    public void setOrderNumber(String orderNumber) {
        this.orderNumber = orderNumber;
    }

    public String getType() {
        return this.type;
    }

    public void setType(String type) {
        this.type = type;
    }

    public String getStatus() {
        return this.status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public String getCustomerEvidence() {
        return this.customerEvidence;
    }

    public void setCustomerEvidence(String customerEvidence) {
        this.customerEvidence = customerEvidence;
    }

    public String getRiderEvidence() {
        return this.riderEvidence;
    }

    public void setRiderEvidence(String riderEvidence) {
        this.riderEvidence = riderEvidence;
    }

    public String getRestaurantEvidence() {
        return this.restaurantEvidence;
    }

    public void setRestaurantEvidence(String restaurantEvidence) {
        this.restaurantEvidence = restaurantEvidence;
    }

    public String getResolutionNotes() {
        return this.resolutionNotes;
    }

    public void setResolutionNotes(String resolutionNotes) {
        this.resolutionNotes = resolutionNotes;
    }

    public String getResolution() {
        return this.resolution;
    }

    public void setResolution(String resolution) {
        this.resolution = resolution;
    }

    public Double getRefundAmount() {
        return this.refundAmount;
    }

    public void setRefundAmount(Double refundAmount) {
        this.refundAmount = refundAmount;
    }

    public Long getResolvedBy() {
        return this.resolvedBy;
    }

    public void setResolvedBy(Long resolvedBy) {
        this.resolvedBy = resolvedBy;
    }

    public String getResolvedAt() {
        return this.resolvedAt;
    }

    public void setResolvedAt(String resolvedAt) {
        this.resolvedAt = resolvedAt;
    }

    public String getCreatedAt() {
        return this.createdAt;
    }

    public void setCreatedAt(String createdAt) {
        this.createdAt = createdAt;
    }

    public static class Builder {
        private Long id;
        private Long orderId;
        private String orderNumber;
        private String type;
        private String status;
        private String customerEvidence;
        private String riderEvidence;
        private String restaurantEvidence;
        private String resolutionNotes;
        private String resolution;
        private Double refundAmount;
        private Long resolvedBy;
        private String resolvedAt;
        private String createdAt;

        public Builder id(Long id) {
            this.id = id;
            return this;
        }

        public Builder orderId(Long orderId) {
            this.orderId = orderId;
            return this;
        }

        public Builder orderNumber(String orderNumber) {
            this.orderNumber = orderNumber;
            return this;
        }

        public Builder type(String type) {
            this.type = type;
            return this;
        }

        public Builder status(String status) {
            this.status = status;
            return this;
        }

        public Builder customerEvidence(String customerEvidence) {
            this.customerEvidence = customerEvidence;
            return this;
        }

        public Builder riderEvidence(String riderEvidence) {
            this.riderEvidence = riderEvidence;
            return this;
        }

        public Builder restaurantEvidence(String restaurantEvidence) {
            this.restaurantEvidence = restaurantEvidence;
            return this;
        }

        public Builder resolutionNotes(String resolutionNotes) {
            this.resolutionNotes = resolutionNotes;
            return this;
        }

        public Builder resolution(String resolution) {
            this.resolution = resolution;
            return this;
        }

        public Builder refundAmount(Double refundAmount) {
            this.refundAmount = refundAmount;
            return this;
        }

        public Builder resolvedBy(Long resolvedBy) {
            this.resolvedBy = resolvedBy;
            return this;
        }

        public Builder resolvedAt(String resolvedAt) {
            this.resolvedAt = resolvedAt;
            return this;
        }

        public Builder createdAt(String createdAt) {
            this.createdAt = createdAt;
            return this;
        }

        public DisputeResponse build() {
            return new DisputeResponse(this.id, this.orderId, this.orderNumber, this.type, this.status, this.customerEvidence, this.riderEvidence, this.restaurantEvidence, this.resolutionNotes, this.resolution, this.refundAmount, this.resolvedBy, this.resolvedAt, this.createdAt);
        }
    }
}

