package com.bhukkad.common.mtls;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class MtlsConfigTest {

    @Test
    void defaultValues_areSensible() {
        MtlsConfig config = new MtlsConfig();
        assertThat(config.isClientAuth()).isFalse();
        assertThat(config.getKeyStoreType()).isEqualTo("PKCS12");
        assertThat(config.getTrustStoreType()).isEqualTo("PKCS12");
    }

    @Test
    void setters_updateValues() {
        MtlsConfig config = new MtlsConfig();
        config.setClientAuth(true);
        config.setKeyStore("/etc/keystore.p12");
        config.setKeyStorePassword("secret");
        config.setTrustStore("/etc/truststore.p12");
        config.setTrustStorePassword("secret");
        config.setKeyStoreType("JKS");
        config.setTrustStoreType("JKS");

        assertThat(config.isClientAuth()).isTrue();
        assertThat(config.getKeyStore()).isEqualTo("/etc/keystore.p12");
        assertThat(config.getKeyStorePassword()).isEqualTo("secret");
        assertThat(config.getTrustStore()).isEqualTo("/etc/truststore.p12");
        assertThat(config.getTrustStorePassword()).isEqualTo("secret");
        assertThat(config.getKeyStoreType()).isEqualTo("JKS");
        assertThat(config.getTrustStoreType()).isEqualTo("JKS");
    }
}
