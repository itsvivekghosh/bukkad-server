package com.bhukkad.gateway;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Limits for strict JSON request validation at the gateway edge.
 *
 * <p>The defaults are deliberately conservative but permissive enough for the
 * platform's normal request shapes. Non-positive configured values fall back to
 * these defaults so a bad deployment value cannot disable validation.</p>
 */
@Component
@ConfigurationProperties(prefix = "app.gateway.validation")
public class GatewayValidationProperties {

    static final int DEFAULT_MAX_DEPTH = 10;
    static final int DEFAULT_MAX_KEY_COUNT = 1000;

    private int maxDepth = DEFAULT_MAX_DEPTH;
    private int maxKeyCount = DEFAULT_MAX_KEY_COUNT;

    public int getMaxDepth() {
        return maxDepth > 0 ? maxDepth : DEFAULT_MAX_DEPTH;
    }

    public void setMaxDepth(int maxDepth) {
        this.maxDepth = maxDepth;
    }

    public int getMaxKeyCount() {
        return maxKeyCount > 0 ? maxKeyCount : DEFAULT_MAX_KEY_COUNT;
    }

    public void setMaxKeyCount(int maxKeyCount) {
        this.maxKeyCount = maxKeyCount;
    }
}
