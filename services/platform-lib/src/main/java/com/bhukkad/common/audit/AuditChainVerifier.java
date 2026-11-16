package com.bhukkad.common.audit;

import java.util.List;

/**
 * Tamper-verification utility for the audit hash chain.
 *
 * <p>{@link #verifyChain(List)} recomputes each record's hash from its fields
 * (using {@link AuditEventService#hashChain}) and compares it to the stored
 * {@code currentHash}, and checks that each record's {@code prevHash} matches
 * the previous record's {@code currentHash}. It returns the index of the first
 * inconsistent record, or {@code -1} when the chain is intact.</p>
 */
public final class AuditChainVerifier {

    private AuditChainVerifier() {
    }

    public static int verifyChain(List<AuditEvent> events) {
        String prevHash = AuditEvent.GENESIS_PREV_HASH;
        for (int i = 0; i < events.size(); i++) {
            AuditEvent event = events.get(i);
            String expected = AuditEventService.hashChain(
                    prevHash,
                    event.getTimestamp(),
                    event.getActorId(),
                    event.getAction(),
                    event.getResourceType(),
                    event.getResourceId(),
                    event.getBefore(),
                    event.getAfter(),
                    event.getCorrelationId(),
                    event.getDecision());
            if (!expected.equals(event.getCurrentHash())) {
                return i;
            }
            if (!prevHash.equals(event.getPrevHash())) {
                return i;
            }
            prevHash = event.getCurrentHash();
        }
        return -1;
    }
}