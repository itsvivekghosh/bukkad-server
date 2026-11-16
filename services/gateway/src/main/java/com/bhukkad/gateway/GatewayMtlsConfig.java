package com.bhukkad.gateway;

import com.bhukkad.common.security.mtls.MtlsConfig;
import com.bhukkad.common.security.mtls.MtlsProperties;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.cloud.gateway.config.HttpClientProperties;
import org.springframework.cloud.gateway.config.GatewayAutoConfiguration$NettyConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.boot.web.server.ServerProperties;
import org.springframework.boot.ssl.SslBundles;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Configuration
@ConditionalOnClass(GatewayAutoConfiguration$NettyConfiguration.class)
@EnableConfigurationProperties({MtlsProperties.class})
public class GatewayMtlsConfig {

    private static final Logger log = LoggerFactory.getLogger(GatewayMtlsConfig.class);

    @Bean
    public GatewayAutoConfiguration$NettyConfiguration.HttpClientSslConfigurer gatewayHttpClientSslConfigurer(
            ServerProperties serverProperties,
            HttpClientProperties httpClientProperties,
            SslBundles sslBundles) {
        return new GatewayAutoConfiguration$NettyConfiguration.HttpClientSslConfigurer(httpClientProperties, serverProperties, sslBundles);
    }

    @Bean
    public MtlsConfig mtlsConfig(MtlsProperties mtlsProperties, org.springframework.core.env.Environment environment) {
        return new MtlsConfig(mtlsProperties, environment);
    }

    @Bean
    @ConditionalOnProperty(name = "app.mtls.enabled", havingValue = "true")
    public io.netty.handler.ssl.SslContext gatewayClientSslContext(MtlsConfig mtlsConfig) {
        if (mtlsConfig == null) return null;
        return mtlsConfig.clientSslContext();
    }
}