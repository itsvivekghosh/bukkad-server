package com.bhukkad.common.datasource;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.beans.factory.InitializingBean;

/**
 * Applies {@code app.datasource.replica.write-fence-ms} (V-17) to the static
 * {@link WriteFenceContext} at startup.
 *
 * <p>This lives in {@code datasource/**} (not {@code config/**}) because the
 * routing datasource instance is built inside the config-owned
 * {@code DataSourceConfig} as a non-bean wrapped by a
 * {@code LazyConnectionDataSourceProxy}, so the TTL can only reach it via
 * ambient state. Env var form: {@code APP_DATASOURCE_REPLICA_WRITE_FENCE_MS}.</p>
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(ReplicaRoutingProperties.class)
public class ReplicaRoutingConfig implements InitializingBean {

    private final ReplicaRoutingProperties properties;

    public ReplicaRoutingConfig(ReplicaRoutingProperties properties) {
        this.properties = properties;
    }

    @Override
    public void afterPropertiesSet() {
        WriteFenceContext.setWriteFenceMs(properties.getWriteFenceMs());
    }
}
