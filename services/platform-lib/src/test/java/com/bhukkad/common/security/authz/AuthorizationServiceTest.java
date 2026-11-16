package com.bhukkad.common.security.authz;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class AuthorizationServiceTest {

    private static final String POLICY_ID = "resource-ownership";

    private SimpleMeterRegistry meterRegistry;
    private AtomicInteger denials;
    private AuthorizationService service;

    @BeforeEach
    void setUp() {
        meterRegistry = new SimpleMeterRegistry();
        denials = new AtomicInteger();
        ResourceOwnershipPolicy ownershipPolicy = new ResourceOwnershipPolicy(POLICY_ID, "order");
        service = new AuthorizationService(List.of(ownershipPolicy), meterRegistry,
                (decision, request) -> denials.incrementAndGet());
    }

    @Test
    void allowsOwner() {
        AuthorizationDecision decision = service.check(POLICY_ID,
                request(7L, "CUSTOMER", 7L));

        assertThat(decision.allowed()).isTrue();
        assertThat(decision.policyId()).isEqualTo(POLICY_ID);
    }

    @Test
    void deniesOtherCustomer() {
        AuthorizationDecision decision = service.check(POLICY_ID,
                request(8L, "CUSTOMER", 7L));

        assertThat(decision.allowed()).isFalse();
        assertThat(denials).hasValue(1);
    }

    @Test
    void allowsAdmin() {
        AuthorizationDecision decision = service.check(POLICY_ID,
                request(99L, "ADMIN", 7L));

        assertThat(decision.allowed()).isTrue();
    }

    @Test
    void recordsEveryDecisionMetric() {
        service.check(POLICY_ID, request(7L, "CUSTOMER", 7L));
        service.check(POLICY_ID, request(8L, "CUSTOMER", 7L));

        assertThat(meterRegistry.get(AuthorizationService.DECISIONS_METRIC)
                .tags("result", "allowed", "policy", POLICY_ID)
                .counter().count()).isEqualTo(1.0);
        assertThat(meterRegistry.get(AuthorizationService.DECISIONS_METRIC)
                .tags("result", "denied", "policy", POLICY_ID)
                .counter().count()).isEqualTo(1.0);
    }

    @Test
    void unknownPolicyDeniesByDefault() {
        AuthorizationDecision decision = service.check("missing-policy",
                request(7L, "CUSTOMER", 7L));

        assertThat(decision.allowed()).isFalse();
        assertThat(decision.policyId()).isEqualTo("missing-policy");
        assertThat(meterRegistry.get(AuthorizationService.DECISIONS_METRIC)
                .tags("result", "denied", "policy", "missing-policy")
                .counter().count()).isEqualTo(1.0);
    }

    private static AuthorizationRequest request(Long subject, String scope, Long ownerId) {
        return new AuthorizationRequest(subject, Set.of(), Set.of(scope),
                "read", "order", "42", Map.of(ResourceOwnershipPolicy.OWNER_ID_ATTRIBUTE, ownerId));
    }
}
