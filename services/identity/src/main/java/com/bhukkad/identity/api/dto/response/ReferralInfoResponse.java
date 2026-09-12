package com.bhukkad.identity.api.dto.response;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class ReferralInfoResponse {
    private String referralCode;
    private int referralsCount;
    private double referralBonusEarned;
}
