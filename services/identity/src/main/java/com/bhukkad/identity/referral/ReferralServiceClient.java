package com.bhukkad.identity.referral;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/**
 * Mesh client for the referral service's internal APIs (ADR-005: the
 * referral module is the SINGLE code generator; identity no longer generates
 * "BK + id" codes itself). Built like the other mesh clients (realtime
 * {@code OrderOwnershipClient}): the shared Spring Boot {@link RestClient.Builder}
 * factory plus explicit connect/read timeouts, and the service-JWT on the
 * {@code X-Service-Token} header of every {@code /internal/**} call.
 *
 * <p>When the referral service is unreachable, {@link #generateCode} returns
 * {@code null}; the caller degrades to a local code so registration is never
 * blocked, and the customer can be (re)issued a code later via the referral
 * service's idempotent code endpoint.</p>
 */
@Service
public class ReferralServiceClient {

    private static final Logger log = LoggerFactory.getLogger(ReferralServiceClient.class);

    private final RestClient restClient;
    private final com.bhukkad.common.security.ServiceJwtAuthTokenProvider authTokenProvider;

    public ReferralServiceClient(RestClient.Builder restClientBuilder,
                                 @Value("${app.services.referral.url:http://referral:8084}") String referralServiceUrl,
                                 org.springframework.beans.factory.ObjectProvider<com.bhukkad.common.security.ServiceJwtAuthTokenProvider> authTokenProvider) {
        org.springframework.http.client.SimpleClientHttpRequestFactory factory =
                new org.springframework.http.client.SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(2000);
        factory.setReadTimeout(3000);
        this.restClient = restClientBuilder
                .baseUrl(referralServiceUrl)
                .requestFactory(factory)
                .defaultHeaders(headers -> headers.setContentType(MediaType.APPLICATION_JSON))
                .build();
        this.authTokenProvider = authTokenProvider.getIfAvailable();
    }

    /** Resolved per call: mesh tokens are short-lived (5 min). */
    private String serviceToken() {
        return authTokenProvider == null ? null : authTokenProvider.serviceToken();
    }

    /**
     * @return the customer's (idempotently generated) referral code from the
     *         referral service, or {@code null} when it cannot be reached.
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

    /**
     * Registers a referral binding for a new signup (referral service is the
     * binding owner per ADR-005).
     *
     * @return {@code true} when the referral service accepted the apply.
     * @throws com.bhukkad.common.error.BusinessException when the referral
     *         service rejects the apply as a business error (e.g. invalid
     *         code) — matching the pre-delegation contract where an invalid
     *         referral code failed registration with a 4xx.
     */
    public boolean applyReferral(Long customerId, String customerEmail, String referralCode) {
        try {
            RestClient.RequestBodySpec request = restClient.post()
                    .uri("/api/v1/referrals/internal/apply");
            String token = serviceToken();
            if (token != null) {
                request = request.header("X-Service-Token", token);
            }
            request.body(new ApplyRequest(referralCode, customerId, customerEmail))
                    .retrieve()
                    .toBodilessEntity();
            return true;
        } catch (org.springframework.web.client.HttpClientErrorException e) {
            // Business rejection from the referral service (4xx): invalid
            // code, self-referral, rate limit — surface it to the caller.
            log.warn("REFERRAL_APPLY_REJECTED customerId={} status={} error={}",
                    customerId, e.getStatusCode(), e.getMessage());
            throw new com.bhukkad.common.error.BusinessException("Invalid referral code");
        } catch (RestClientException e) {
            log.error("REFERRAL_APPLY_FAILED customerId={} error={}", customerId, e.getMessage());
            return false;
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

    private record ApplyRequest(String referralCode, Long customerId, String customerEmail) {
        public String getReferralCode() {
            return referralCode;
        }

        public Long getCustomerId() {
            return customerId;
        }

        public String getCustomerEmail() {
            return customerEmail;
        }
    }
}
