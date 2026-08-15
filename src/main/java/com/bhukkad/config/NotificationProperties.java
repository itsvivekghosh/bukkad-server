package com.bhukkad.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

@Data
@ConfigurationProperties(prefix = "app.notification")
public class NotificationProperties {

    private boolean enabled = true;
    private Email email = new Email();
    private Sms sms = new Sms();
    private Whatsapp whatsapp = new Whatsapp();
    private Push push = new Push();

    @Data
    public static class Email {
        private boolean enabled = false;
        private String from = "noreply@bhukkad.com";
    }

    @Data
    public static class Sms {
        private boolean enabled = false;
        private String provider = "log";
        private Twilio twilio = new Twilio();
    }

    @Data
    public static class Twilio {
        private String accountSid = "";
        private String authToken = "";
        private String fromNumber = "";
        private String whatsappFromNumber = "";
    }

    @Data
    public static class Whatsapp {
        private boolean enabled = false;
        private String provider = "log";
        private Twilio twilio = new Twilio();
    }

    @Data
    public static class Push {
        private boolean enabled = false;
        private String provider = "log";
        private Fcm fcm = new Fcm();
    }

    @Data
    public static class Fcm {
        private String serverKey = "";
        private String projectId = "";
    }
}
