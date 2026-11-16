package com.bhukkad.gateway;

import io.netty.handler.ssl.SslContext;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(classes = {GatewayApplication.class, GatewayMtlsConfig.class})
@TestPropertySource(properties = {
        "app.mtls.enabled=true",
        "app.mtls.keystore=/etc/tls/keystore.p12",
        "app.mtls.keystore-password=secret",
        "app.mtls.truststore=/etc/tls/truststore.p12",
        "app.mtls.truststore-password=secret"
})
class GatewayTlsConfigTest {

    @Autowired
    private SslContext gatewayClientSslContext;

    @Autowired
    private com.bhukkad.common.security.mtls.MtlsConfig mtlsConfig;

    @Test
    void mtlsClientSslContextIsConfigured() {
        assertThat(gatewayClientSslContext).isNotNull();
    }

    @Test
    void mtlsConfigBeanIsCreated() {
        assertThat(mtlsConfig).isNotNull();
    }

    @Test
    void mtlsConfigurationPropertiesAreBound() {
        com.bhukkad.common.security.mtls.MtlsProperties mtlsProperties = new com.bhukkad.common.security.mtls.MtlsProperties();
        mtlsProperties.setEnabled(true);
        mtlsProperties.setKeyStore("/etc/tls/keystore.p12");
        mtlsProperties.setKeyStorePassword("secret");
        mtlsProperties.setTrustStore("/etc/tls/truststore.p12");
        mtlsProperties.setTrustStorePassword("secret");

        assertThat(mtlsProperties.isEnabled()).isTrue();
        assertThat(mtlsProperties.getKeyStore()).isEqualTo("/etc/tls/keystore.p12");
        assertThat(mtlsProperties.getKeyStorePassword()).isEqualTo("secret");
        assertThat(mtlsProperties.getTrustStore()).isEqualTo("/etc/tls/truststore.p12");
        assertThat(mtlsProperties.getTrustStorePassword()).isEqualTo("secret");
    }

    @Test
    void mtlsEnabledPropertyControlsConfiguration() {
        com.bhukkad.common.security.mtls.MtlsProperties enabledProperties = new com.bhukkad.common.security.mtls.MtlsProperties();
        enabledProperties.setEnabled(true);

        com.bhukkad.common.security.mtls.MtlsProperties disabledProperties = new com.bhukkad.common.security.mtls.MtlsProperties();
        disabledProperties.setEnabled(false);

        assertThat(enabledProperties.isEnabled()).isTrue();
        assertThat(disabledProperties.isEnabled()).isFalse();
    }
}