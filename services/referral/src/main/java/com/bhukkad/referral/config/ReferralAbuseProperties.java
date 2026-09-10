package com.bhukkad.referral.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Referral apply abuse ceilings (audit feature #4 / ADR-005): per-customer
 * and per-IP atomic fixed-window limits enforced on the apply surface.
 */
@ConfigurationProperties(prefix = "app.referral.abuse")
public class ReferralAbuseProperties {

    /** Maximum apply attempts per customer per day. */
    private int applyPerCustomerPerDay = 5;

    /** Maximum apply attempts per source IP per day. */
    private int applyPerIpPerDay = 20;

    public int getApplyPerCustomerPerDay() {
        return applyPerCustomerPerDay;
    }

    public void setApplyPerCustomerPerDay(int applyPerCustomerPerDay) {
        this.applyPerCustomerPerDay = applyPerCustomerPerDay;
    }

    public int getApplyPerIpPerDay() {
        return applyPerIpPerDay;
    }

    public void setApplyPerIpPerDay(int applyPerIpPerDay) {
        this.applyPerIpPerDay = applyPerIpPerDay;
    }
}
