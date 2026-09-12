package com.bhukkad.delivery.domain.entity;

/**
 * JPQL constructor-expression result: number of not-yet-delivered
 * assignments per agent (ADR-003 capacity check input).
 */
public record AgentActiveLoad(Long agentId, long activeCount) {
}
