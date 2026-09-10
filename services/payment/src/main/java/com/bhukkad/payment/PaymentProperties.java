package com.bhukkad.payment;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

@Data
@ConfigurationProperties(prefix = "app.payment")
public class PaymentProperties {

    /** Gateway strategy: {@code razorpay} or {@code simulated} (dev default). */
    private String gateway = "simulated";
    private Razorpay razorpay = new Razorpay();

    @Data
    public static class Razorpay {
        private boolean enabled = false;
        private String keyId = "";
        private String keySecret = "";
        private String webhookSecret = "";
        private String currency = "INR";
        private String baseUrl = "https://api.razorpay.com/v1";
    }
}
