package com.bhukkad.payment;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

@Data
@ConfigurationProperties(prefix = "app.payment")
public class PaymentProperties {

    private String provider = "simulated";
    private Razorpay razorpay = new Razorpay();

    @Data
    public static class Razorpay {
        private boolean enabled = false;
        private String keyId = "";
        private String keySecret = "";
        private String webhookSecret = "";
        private String currency = "INR";
    }
}
