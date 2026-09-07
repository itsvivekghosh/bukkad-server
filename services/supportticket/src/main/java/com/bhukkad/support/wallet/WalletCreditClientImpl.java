package com.bhukkad.support.wallet;

import com.bhukkad.common.security.ServiceJwtAuthTokenProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.math.BigDecimal;
import java.time.Duration;

/**
 * Real implementation that credits a customer's wallet via the payment
 * service's internal wallet endpoint ({@code /api/v1/internal/wallet/credit}).
 *
 * <p>This replaces the previous log-only stub so dispute refunds actually
 * move money. The call is authenticated with a service-to-service JWT
 * ({@code X-Service-Token}) and carries connect/read timeouts.</p>
 */
@Service
public class WalletCreditClientImpl implements WalletCreditClient {

    private static final Logger log = LoggerFactory.getLogger(WalletCreditClientImpl.class);

    private final RestClient restClient;
    private final String paymentServiceUrl;
    private final ServiceJwtAuthTokenProvider authTokenProvider;

    public WalletCreditClientImpl(RestClient.Builder restClientBuilder,
                                  @Value("${app.services.payment.url:http://payment:8080}") String paymentServiceUrl,
                                  ServiceJwtAuthTokenProvider authTokenProvider) {
        this.restClient = restClientBuilder
                .baseUrl(paymentServiceUrl)
                .defaultHeaders(headers -> {
                    headers.setContentType(MediaType.APPLICATION_JSON);
                    String token = authTokenProvider.serviceToken();
                    if (token != null) {
                        headers.set("X-Service-Token", token);
                    }
                })
                .build();
        this.paymentServiceUrl = paymentServiceUrl;
        this.authTokenProvider = authTokenProvider;
    }

    @Override
    public void credit(Long userId, double amount, String refId, Long paymentId, String description) {
        if (amount <= 0) {
            log.warn("REFUND_SKIPPED non-positive amount: userId={} amount={}", userId, amount);
            return;
        }

        try {
            restClient.post()
                    .uri("/api/v1/internal/wallet/credit")
                    .body(new CreditRequest(userId, BigDecimal.valueOf(amount),
                            "DISPUTE_REFUND:" + description,
                            "dispute:" + refId + ":payment:" + paymentId))
                    .retrieve()
                    .toBodilessEntity();
            log.info("REFUND_CREDITED userId={} amount={} refId={} paymentId={}",
                    userId, amount, refId, paymentId);
        } catch (RestClientException e) {
            log.error("REFUND_FAILED userId={} amount={} refId={} error={}",
                    userId, amount, refId, e.getMessage());
            throw new RuntimeException("Wallet credit failed; refund not applied: " + e.getMessage(), e);
        }
    }

    /** Request body matching the payment service's {@code WalletController.credit}. */
    private static final class CreditRequest {
        private Long customerId;
        private BigDecimal amount;
        private String type;
        private String reference;

        @SuppressWarnings("unused")
        CreditRequest() {}

        CreditRequest(Long customerId, BigDecimal amount, String type, String reference) {
            this.customerId = customerId;
            this.amount = amount;
            this.type = type;
            this.reference = reference;
        }

        // Getters for Jackson serialization
        public Long getCustomerId() { return customerId; }
        public BigDecimal getAmount() { return amount; }
        public String getType() { return type; }
        public String getReference() { return reference; }
    }
}
