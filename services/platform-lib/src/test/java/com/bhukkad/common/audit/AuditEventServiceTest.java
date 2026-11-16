package com.bhukkad.common.audit;

import com.bhukkad.common.outbox.OutboxEventRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifies the immutable audit hash chain: recording {@code N} events yields a
 * valid chain, and tampering with any middle event breaks verification at the
 * tampered index.
 */
class AuditEventServiceTest {

    @Test
    void recordingNEventsProducesValidChain() {
        int n = 10;
        List<AuditEvent> chain = new ArrayList<>();
        String prevHash = AuditEvent.GENESIS_PREV_HASH;
        for (int i = 0; i < n; i++) {
            AuditEvent event = new AuditEvent();
            event.setId(UUID.randomUUID());
            event.setTimestamp(Instant.now());
            event.setActorId("actor-" + i);
            event.setAction(AuditEventAction.UPDATE);
            event.setResourceType("ORDER");
            event.setResourceId(String.valueOf(1000 + i));
            event.setBefore("{\"status\":\"OPEN\"}");
            event.setAfter("{\"status\":\"CANCELLED\"}");
            event.setCorrelationId("corr-" + i);
            event.setDecision(AuditDecision.ALLOW);
            event.setPrevHash(prevHash);
            event.setCurrentHash(AuditEventService.hashChain(
                    prevHash, event.getTimestamp(), event.getActorId(), event.getAction(),
                    event.getResourceType(), event.getResourceId(), event.getBefore(),
                    event.getAfter(), event.getCorrelationId(), event.getDecision()));
            chain.add(event);
            prevHash = event.getCurrentHash();
        }

        assertThat(AuditChainVerifier.verifyChain(chain)).isEqualTo(-1);
    }

    @Test
    void tamperingMiddleEventBreaksVerificationAtTamperedIndex() {
        int n = 7;
        List<AuditEvent> chain = buildChain(n);
        int tampered = 3; // middle
        AuditEvent tamperedEvent = chain.get(tampered);
        // Mutate the after-state without recomputing the hash.
        tamperedEvent.setAfter("{\"status\":\"TAMPERED\"}");

        int firstBad = AuditChainVerifier.verifyChain(chain);
        assertThat(firstBad).isEqualTo(tampered);
    }

    @Test
    void tamperingLastEventBreaksVerificationAtLastIndex() {
        int n = 5;
        List<AuditEvent> chain = buildChain(n);
        int last = n - 1;
        chain.get(last).setAfter("{\"status\":\"TAMPERED\"}");

        assertThat(AuditChainVerifier.verifyChain(chain)).isEqualTo(last);
    }

    @Test
    void prevHashLinkMismatchIsDetected() {
        List<AuditEvent> chain = buildChain(4);
        // Sever the link: point event 2's prevHash at a wrong value.
        chain.get(2).setPrevHash("deadbeef" + "0".repeat(56));

        assertThat(AuditChainVerifier.verifyChain(chain)).isEqualTo(2);
    }

    @Test
    void emptyChainIsValid() {
        assertThat(AuditChainVerifier.verifyChain(List.of())).isEqualTo(-1);
    }

    @Test
    void hashIsDeterministic() {
        Instant ts = Instant.parse("2026-11-16T00:00:00Z");
        String h1 = AuditEventService.hashChain(
                "prev", ts, "actor", AuditEventAction.CREATE, "ORDER", "1",
                "{}", "{\"a\":1}", "corr", AuditDecision.ALLOW);
        String h2 = AuditEventService.hashChain(
                "prev", ts, "actor", AuditEventAction.CREATE, "ORDER", "1",
                "{}", "{\"a\":1}", "corr", AuditDecision.ALLOW);
        assertThat(h1).isEqualTo(h2);
        assertThat(h1).hasSize(64); // SHA-256 hex
        assertThat(h1).matches("[0-9a-f]{64}");
    }

    private List<AuditEvent> buildChain(int n) {
        List<AuditEvent> chain = new ArrayList<>();
        String prevHash = AuditEvent.GENESIS_PREV_HASH;
        for (int i = 0; i < n; i++) {
            AuditEvent event = new AuditEvent();
            event.setId(UUID.randomUUID());
            event.setTimestamp(Instant.now());
            event.setActorId("actor-" + i);
            event.setAction(AuditEventAction.CREATE);
            event.setResourceType("ORDER");
            event.setResourceId(String.valueOf(2000 + i));
            event.setBefore("{}");
            event.setAfter("{\"status\":\"CREATED\"}");
            event.setCorrelationId("corr-" + i);
            event.setDecision(AuditDecision.ALLOW);
            event.setPrevHash(prevHash);
            event.setCurrentHash(AuditEventService.hashChain(
                    prevHash, event.getTimestamp(), event.getActorId(), event.getAction(),
                    event.getResourceType(), event.getResourceId(), event.getBefore(),
                    event.getAfter(), event.getCorrelationId(), event.getDecision()));
            chain.add(event);
            prevHash = event.getCurrentHash();
        }
        return chain;
    }
}
