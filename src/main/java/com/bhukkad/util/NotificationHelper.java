package com.bhukkad.util;

import com.bhukkad.config.GiftCardProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.util.UriComponentsBuilder;

@Component
@Slf4j
public class NotificationHelper {

    private final RestTemplate restTemplate;
    private final GiftCardProperties giftCardProperties;

    @Autowired(required = false)
    private JavaMailSender mailSender;

    @Value("${app.notification.webhook.timeout-ms:5000}")
    private int webhookTimeout;

    public NotificationHelper(RestTemplate restTemplate, GiftCardProperties giftCardProperties) {
        this.restTemplate = restTemplate;
        this.giftCardProperties = giftCardProperties;
    }

    public void sendWebhookNotification(String webhookUrl, String message) {
        try {
            HttpHeaders headers = new HttpHeaders();
            headers.set("Content-Type", "application/json");

            String jsonBody = String.format("{\"text\":\"%s\"}", message.replace("\"", "\\\""));

            HttpEntity<String> request = new HttpEntity<>(jsonBody, headers);

            restTemplate.exchange(
                    webhookUrl,
                    HttpMethod.POST,
                    request,
                    String.class,
                    UriComponentsBuilder.fromHttpUrl(webhookUrl).build().toUri()
            );

            log.debug("Webhook notification sent to {}", webhookUrl);
        } catch (Exception ex) {
            log.warn("Failed to send webhook notification to {}: {}", webhookUrl, ex.getMessage());
        }
    }

    public void sendGiftCardNotification(String recipientEmail, String recipientName, String code, Double amount, String message) {
        try {
            if ("true".equalsIgnoreCase(giftCardProperties.getNotificationEmailEnabled()) && mailSender != null) {
                SimpleMailMessage mailMessage = new SimpleMailMessage();
                mailMessage.setTo(recipientEmail);
                mailMessage.setFrom(giftCardProperties.getSenderEmail());
                mailMessage.setSubject("You've received a Bhukkad Gift Card!");
                mailMessage.setText(buildGiftCardEmailBody(recipientName, code, amount, message));

                mailSender.send(mailMessage);
                log.info("Gift card email sent to {}", recipientEmail);
            }
        } catch (Exception ex) {
            log.warn("Failed to send gift card email to {}: {}", recipientEmail, ex.getMessage());
        }
    }

    private String buildGiftCardEmailBody(String recipientName, String code, Double amount, String message) {
        StringBuilder sb = new StringBuilder();
        sb.append("Hi ").append(recipientName).append(",\n\n");
        sb.append("You've received a Bhukkad Gift Card worth Rs. ").append(amount).append("!\n\n");
        if (message != null && !message.isBlank()) {
            sb.append("Message: ").append(message).append("\n\n");
        }
        sb.append("Use code: ").append(code).append("\n");
        sb.append("Redeem at checkout on the Bhukkad app or website.\n\n");
        sb.append("Enjoy your meal!\n");
        sb.append("Team Bhukkad");
        return sb.toString();
    }
}