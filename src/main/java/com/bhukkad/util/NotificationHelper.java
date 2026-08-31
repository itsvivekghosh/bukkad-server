package com.bhukkad.util;

import com.bhukkad.config.GiftCardProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.client.HttpComponentsClientHttpRequestFactory;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestTemplate;

import org.apache.hc.client5.http.config.RequestConfig;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManager;
import org.apache.hc.core5.util.Timeout;

@Component
@Slf4j
public class NotificationHelper {

    private final RestTemplate restTemplate;
    private final RestTemplate webhookRestTemplate;
    private final GiftCardProperties giftCardProperties;

    @Autowired(required = false)
    private JavaMailSender mailSender;

    public NotificationHelper(RestTemplate restTemplate,
                              GiftCardProperties giftCardProperties,
                              @Value("${app.notification.webhook.timeout-ms:5000}") int webhookTimeout) {
        this.restTemplate = restTemplate;
        this.giftCardProperties = giftCardProperties;
        this.webhookRestTemplate = createWebhookRestTemplate(webhookTimeout);
    }

    private static RestTemplate createWebhookRestTemplate(int webhookTimeout) {
        PoolingHttpClientConnectionManager connectionManager = new PoolingHttpClientConnectionManager();
        connectionManager.setMaxTotal(50);
        connectionManager.setDefaultMaxPerRoute(20);

        RequestConfig requestConfig = RequestConfig.custom()
                .setConnectTimeout(Timeout.ofMilliseconds(webhookTimeout))
                .setResponseTimeout(Timeout.ofMilliseconds(webhookTimeout))
                .setConnectionRequestTimeout(Timeout.ofMilliseconds(1000))
                .build();

        CloseableHttpClient httpClient = HttpClients.custom()
                .setConnectionManager(connectionManager)
                .setDefaultRequestConfig(requestConfig)
                .evictIdleConnections(Timeout.ofSeconds(30))
                .build();

        return new RestTemplate(new HttpComponentsClientHttpRequestFactory(httpClient));
    }

    public void sendWebhookNotification(String webhookUrl, String message) {
        try {
            HttpHeaders headers = new HttpHeaders();
            headers.set("Content-Type", "application/json");

            String jsonBody = String.format("{\"text\":\"%s\"}", message.replace("\"", "\\\""));

            HttpEntity<String> request = new HttpEntity<>(jsonBody, headers);

            webhookRestTemplate.exchange(
                    webhookUrl,
                    HttpMethod.POST,
                    request,
                    String.class
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