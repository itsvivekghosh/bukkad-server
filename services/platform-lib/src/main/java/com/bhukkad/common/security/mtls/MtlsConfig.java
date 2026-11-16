package com.bhukkad.common.security.mtls;

import io.netty.handler.ssl.SslContext;
import io.netty.handler.ssl.SslContextBuilder;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.TrustManagerFactory;
import java.io.FileInputStream;
import java.security.KeyStore;

/**
 * mTLS configuration: builds a Netty client {@link SslContext} for upstream
 * calls and a Tomcat SSL customizer for accepting mutual-TLS connections.
 *
 * <p>Disabled by default ({@code app.mtls.enabled=false}). Enable in prod only
 * after cert-manager + internal CA are deployed (see k8s/components/tls-internal).
 */
@Configuration
@EnableConfigurationProperties(MtlsProperties.class)
public class MtlsConfig {

    private final MtlsProperties mtlsProperties;
    private final Environment environment;

    public MtlsConfig(MtlsProperties mtlsProperties, Environment environment) {
        this.mtlsProperties = mtlsProperties;
        this.environment = environment;
    }

    private boolean isEnabled() {
        return mtlsProperties.isEnabled();
    }

    private boolean isProdEnvironment() {
        return environment.matchesProfiles("prod")
                || "prod".equals(environment.getProperty("spring.profiles.active"));
    }

    @Bean
    @org.springframework.boot.autoconfigure.condition.ConditionalOnProperty(
            name = "app.mtls.enabled", havingValue = "true")
    public SslContext clientSslContext() {
        if (!isEnabled()) {
            return null;
        }
        try {
            KeyStore keyStore = KeyStore.getInstance(mtlsProperties.getKeyStoreType());
            char[] keyPwd = (mtlsProperties.getKeyStorePassword() == null
                    ? new char[0] : mtlsProperties.getKeyStorePassword().toCharArray());
            try (FileInputStream fis = new FileInputStream(mtlsProperties.getKeyStore())) {
                keyStore.load(fis, keyPwd);
            }
            KeyManagerFactory kmf = KeyManagerFactory.getInstance(
                    KeyManagerFactory.getDefaultAlgorithm());
            kmf.init(keyStore, keyPwd);

            KeyStore trustStore = KeyStore.getInstance(mtlsProperties.getTrustStoreType());
            char[] trustPwd = (mtlsProperties.getTrustStorePassword() == null
                    ? new char[0] : mtlsProperties.getTrustStorePassword().toCharArray());
            try (FileInputStream fis = new FileInputStream(mtlsProperties.getTrustStore())) {
                trustStore.load(fis, trustPwd);
            }
            TrustManagerFactory tmf = TrustManagerFactory.getInstance(
                    TrustManagerFactory.getDefaultAlgorithm());
            tmf.init(trustStore);

            return SslContextBuilder.forClient()
                    .keyManager(kmf)
                    .trustManager(tmf)
                    .protocols("TLSv1.3", "TLSv1.2")
                    .build();
        } catch (Exception e) {
            throw new IllegalStateException("Failed to configure mTLS client SSL context", e);
        }
    }

    @Bean
    public org.springframework.boot.web.server.WebServerFactoryCustomizer<
            org.springframework.boot.web.server.ConfigurableWebServerFactory> serverSslCustomizer() {
        return factory -> {
            if (!isEnabled()) {
                return;
            }
            org.springframework.boot.web.server.Ssl ssl = new org.springframework.boot.web.server.Ssl();
            ssl.setKeyStore(mtlsProperties.getKeyStore());
            ssl.setKeyStorePassword(mtlsProperties.getKeyStorePassword());
            ssl.setKeyStoreType(mtlsProperties.getKeyStoreType());
            ssl.setTrustStore(mtlsProperties.getTrustStore());
            ssl.setTrustStorePassword(mtlsProperties.getTrustStorePassword());
            ssl.setTrustStoreType(mtlsProperties.getTrustStoreType());

            ssl.setClientAuth(isProdEnvironment()
                    ? org.springframework.boot.web.server.Ssl.ClientAuth.NEED
                    : org.springframework.boot.web.server.Ssl.ClientAuth.WANT);

            factory.setSsl(ssl);
        };
    }
}
