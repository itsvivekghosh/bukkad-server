package com.bhukkad.common.storage;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Cache-Control metadata applied to objects uploaded to S3 (and served via CloudFront).
 *
 * <p>Two asset tiers:
 * <ul>
 *   <li>{@link AssetType#VERSIONED} — versioned / public immutable assets (content-addressed
 *       file names such as {@code v1/a1b2c3.png}): cache for one year and mark immutable.</li>
 *   <li>{@link AssetType#DYNAMIC} — dynamic / user-uploaded assets: one day TTL with
 *       {@code stale-while-revalidate} so clients may serve stale while revalidating.</li>
 * </ul>
 *
 * <p>Values are applied as the {@code Cache-Control} header on S3 {@code PutObject}
 * requests by {@link com.bhukkad.common.aws.S3Service}.
 *
 * <p>Configuration prefix: {@code app.storage.cache-control}.
 */
@Getter
@Setter
@ConfigurationProperties(prefix = "app.storage.cache-control")
public class CacheControlProperties {

    /** Versioned / public immutable assets. Default: cache for 1 year, immutable. */
    private String versioned = "public, max-age=31536000, immutable";

    /** Dynamic / user-uploaded assets. Default: 1 day TTL + stale-while-revalidate. */
    private String dynamic = "public, max-age=86400, stale-while-revalidate=86400";

    /** Asset classification used to pick the Cache-Control value. */
    public enum AssetType {
        /** Content-addressed / versioned asset that never changes (immutable). */
        VERSIONED,
        /** Dynamic or user-uploaded asset that may change. */
        DYNAMIC
    }

    /** Cache-Control header value for the given asset type. */
    public String forAsset(AssetType assetType) {
        return switch (assetType) {
            case VERSIONED -> versioned;
            case DYNAMIC -> dynamic;
        };
    }
}
