package com.bhukkad.support.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

public class DisputeRequest {
    @NotNull(message="Dispute type is required")
    private @NotNull(message="Dispute type is required") String type;
    @NotBlank(message="Customer evidence is required for evidence-based resolution")
    private @NotBlank(message="Customer evidence is required for evidence-based resolution") String customerEvidence;

    public String getType() {
        return this.type;
    }

    public void setType(String type) {
        this.type = type;
    }

    public String getCustomerEvidence() {
        return this.customerEvidence;
    }

    public void setCustomerEvidence(String customerEvidence) {
        this.customerEvidence = customerEvidence;
    }
}

