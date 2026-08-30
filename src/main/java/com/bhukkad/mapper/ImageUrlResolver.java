package com.bhukkad.mapper;

/**
 * Resolves a stored (relative) image reference into its public URL.
 *
 * <p>Owned by the mapper package so {@link MenuItemMapper} can depend on this
 * abstraction instead of the storage module — the implementation is provided
 * by {@code storage.MenuImageService}, inverting the mapper→storage
 * dependency (module-cycle guardrail).</p>
 */
@FunctionalInterface
public interface ImageUrlResolver {

    String resolvePublicUrl(String storedValue);
}
