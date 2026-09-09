package com.bhukkad.identity.api;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class MembershipStatusResponse {
    private String status;
}
