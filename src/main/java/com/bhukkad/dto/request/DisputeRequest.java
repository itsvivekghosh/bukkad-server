package com.bhukkad.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

/** Customer request to file a dispute against an order. */
@Data
public class DisputeRequest {

    @NotNull(message = "Dispute type is required")
    private String type;

    @NotBlank(message = "Customer evidence is required for evidence-based resolution")
    private String customerEvidence;
}
