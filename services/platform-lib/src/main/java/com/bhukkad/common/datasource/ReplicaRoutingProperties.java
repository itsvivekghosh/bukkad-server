package com.bhukkad.common.datasource;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * V-17 replica routing knobs (prefix {@code app.datasource.replica}).
 * Complements {@link ReadReplicaProperties} ({@code app.datasource.read-replica}),
 * which owns pool/connection settings — this class owns the routing-fence and
 * monitoring behaviour.
 */
@Data
@ConfigurationProperties(prefix = "app.datasource.replica")
public class ReplicaRoutingProperties {

    /**
     * V-17 write-fence TTL: how long reads on a thread stay pinned to the
     * primary after a primary write commits there (read-your-writes budget,
     * sized above the observed replica replay lag). Default 2000 ms.
     */
    private long writeFenceMs = WriteFenceContext.DEFAULT_WRITE_FENCE_MS;
}
