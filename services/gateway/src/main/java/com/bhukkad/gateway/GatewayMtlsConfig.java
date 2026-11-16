package com.bhukkad.gateway;

import com.bhukkad.common.security.mtls.MtlsConfig;
import com.bhukkad.common.security.mtls.MtlsProperties;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

import io.netty.handler.ssl.SslContext;

/**
 * mTLS configuration for Spring Cloud Gateway.
 *
 * <p>The actual SSL configuration for the gateway's HTTP client is driven by
 * {@code spring.cloud.gateway.httpclient.ssl} properties in application.yml
 * (see {@code services/gateway/src/main/resources/application-prod.yml}).
 * This class only wires the {@link MtlsConfig} bean for the server-side SSL
 * customizer and exposes the client {@link SslContext} for any programmatic use.</p>
 */
@Configuration
@ConditionalOnClass(SslContext.class)
@EnableConfigurationProperties({MtlsProperties.class})
public class GatewayMtlsConfig {

    @Bean
    public MtlsConfig mtlsConfig(MtlsProperties mtlsProperties, Environment environment) {
        return new MtlsConfig(mtlsProperties, environment);
    }

    @Bean
    @org.springframework.boot.autoconfigure.condition.ConditionalOnProperty(name = "app.mtls.enabled", havingValue = "true")
    public io.netty.handler.ssl.SslContext gatewayClientSslContext(MtlsConfig mtlsConfig) {
        if (mtlsConfig == null) return null;
        return mtlsConfig.clientSslContext();
    }
}