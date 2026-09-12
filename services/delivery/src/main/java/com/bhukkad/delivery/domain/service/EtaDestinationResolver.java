package com.bhukkad.delivery.service;

import java.util.Optional;

/**
 * ETA destination seam (ADR-003): resolves the coordinates an assigned
 * order is heading to. The delivery module persists rider positions but not
 * order/restaurant destinations — those live with the order service, so the
 * production wiring arrives with the order-side geo plumbing (out of this
 * batch's scope, mirroring the assign() coordinate note). While no bean
 * implements this interface, {@code DefaultEtaPort} degrades to empty
 * snapshots instead of guessing.
 */
@FunctionalInterface
public interface EtaDestinationResolver {

    /** Destination coordinates for the order, or empty when unknown. */
    Optional<Coordinates> destinationOf(Long orderId);

    record Coordinates(double latitude, double longitude) {
    }
}
