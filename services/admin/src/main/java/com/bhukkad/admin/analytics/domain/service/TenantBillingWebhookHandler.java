package com.bhukkad.admin.analytics.domain.service;

/**
 * Billing webhook ingestion — placeholder.
 *
 * <p>No implementation yet. Wire the billing provider connector (e.g. Stripe
 * webhooks) here: verify the provider signature, map the event to a tenant,
 * and apply idempotent subscription/quota changes. Webhooks may be redelivered,
 * so any implementation must be idempotent (dedupe by event id).</p>
 */
public interface TenantBillingWebhookHandler {

    /**
     * Handles a billing webhook event for a tenant.
     *
     * @param event the incoming webhook event (never {@code null})
     */
    void handle(TenantBillingEvent event);

    /** Shape of an incoming billing webhook event. */
    record TenantBillingEvent(String tenantId, String type, String payload) {
    }
}
