package com.bhukkad.search.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * P-08 open product decision, implemented as the OPTIONAL trigram mode and
 * shipped OFF (ADR-002's "PG-native first" posture extends to fuzzy matching
 * until product flips it). Getters are explicit — this module compiles
 * without Lombok annotation processing.
 *
 * <p>{@code enabled=false} (default) → {@code unifiedSearch} runs exactly the
 * bounded, LIKE-escaped queries that are live today, backed by the V9
 * varchar_pattern_ops prefix indexes. With {@code enabled=true} the same
 * endpoints rank results by {@code similarity()} over the V11 GIN trigram
 * indexes — typo-tolerant. The term stays a bound parameter either way
 * (trigram matching has no pattern semantics, so escaping is moot; injection
 * posture is unchanged).</p>
 */
@ConfigurationProperties(prefix = "app.search.fuzzy")
public class SearchFuzzyProperties {

    /** pg_trgm's own default cutoff — untouched behavior for a flipped env. */
    public static final double DEFAULT_SIMILARITY_THRESHOLD = 0.3;

    /** Master switch for similarity()-ranked search. Default FALSE = LIKE path. */
    private boolean enabled = false;

    /**
     * Per-query word-similarity cutoff passed to the fuzzy repository methods
     * (no GUC mutation). Default equals pg_trgm's 0.3.
     */
    private double similarityThreshold = DEFAULT_SIMILARITY_THRESHOLD;

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public double getSimilarityThreshold() {
        return similarityThreshold;
    }

    public void setSimilarityThreshold(double similarityThreshold) {
        this.similarityThreshold = similarityThreshold;
    }
}
