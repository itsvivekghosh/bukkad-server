package com.bhukkad.growth.client;

import com.bhukkad.common.security.ServiceJwtAuthTokenProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
/**
 * Mesh client for the referral service's internal code-generation API
 * (ADR-005: referral module is the single code generator). Built like the
 * other mesh clients (realtime {@code OrderOwnershipClient}): the shared
 * Spring Boot {@link RestClient.Builder} factory plus explicit connect/read
 * timeouts, and the service-JWT on the {@code X-Service-Token} header of the
 * {@code /internal/**} call.
 *
 * <p>When the referral service is unreachable the caller degrades with a 503
 * ({@code UpstreamUnavailableException}) rather than minting a second class
 * of codes.</p>
 */
@Service
public class ReferralServiceClient {

    private static final Logger log = LoggerFactory.getLogger(ReferralServiceClient.class);

    private final RestClient restClient;
    private final ServiceJwtAuthTokenProvider authTokenProvider;

    public ReferralServiceClient(RestClient.Builder restClientBuilder,
                                 @Value("${app.services.referral.url:http://referral:8084}") String referralServiceUrl,
                                 org.springframework.beans.factory.ObjectProvider<ServiceJwtAuthTokenProvider> authTokenProvider) {
        org.springframework.http.client.SimpleClientHttpRequestFactory factory =
                new org.springframework.http.client.SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(2000);
        factory.setReadTimeout(3000);
        this.restClient = restClientBuilder
                .baseUrl(referralServiceUrl)
                .requestFactory(factory)
                .defaultHeaders(headers -> headers.setContentType(org.springframework.http.MediaType.APPLICATION_JSON))
                .build();
        this.authTokenProvider = authTokenProvider.getIfAvailable();
    }

    /** Resolved per call: mesh tokens are short-lived (5 min). */
    private String serviceToken() {
        return authTokenProvider == null ? null : authTokenProvider.serviceToken();
    }

    /**
     * @return the customer's (idempotently generated) referral code, or
     *         {@code null} when the referral service cannot be reached.
     */
    public String generateCode(Long customerId) {
        try {
            RestClient.RequestBodySpec request = restClient.post()
                    .uri("/api/v1/referrals/internal/code");
            String token = serviceToken();
            if (token != null) {
                request = request.header("X-Service-Token", token);
            }
            CodeResponse response = request
                    .body(new CodeRequest(customerId))
                    .retrieve()
                    .body(CodeResponse.class);
            return response == null ? null : response.referralCode();
        } catch (RestClientException e) {
            log.error("REFERRAL_CODEGEN_UNAVAILABLE customerId={} error={}", customerId, e.getMessage());
            return null;
        }
    }

    private record CodeRequest(Long customerId) {
        public Long getCustomerId() {
            return customerId;
        }
    }

    private record CodeResponse(String referralCode) {
        public String getReferralCode() {
            return referralCode;
        }
    }
}
