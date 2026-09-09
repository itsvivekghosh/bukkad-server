package com.bhukkad.support.dto.request;

import jakarta.validation.constraints.NotNull;

public class DisputeResolveRequest {
    @NotNull(message="Resolution is required")
    private @NotNull(message="Resolution is required") String resolution;
    private Double refundAmount;
    private String notes;

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

    public String getNotes() {
        return this.notes;
    }

    public void setNotes(String notes) {
        this.notes = notes;
    }
}

