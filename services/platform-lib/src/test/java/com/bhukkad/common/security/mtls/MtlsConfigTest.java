package com.bhukkad.common.security.mtls;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class MtlsConfigTest {

    @Test
    void defaultValues_areSensible() {
        MtlsProperties properties = new MtlsProperties();
        assertThat(properties.isEnabled()).isFalse();
        assertThat(properties.getKeyStoreType()).isEqualTo("PKCS12");
        assertThat(properties.getTrustStoreType()).isEqualTo("PKCS12");
    }

    @Test
    void setters_updateValues() {
        MtlsProperties properties = new MtlsProperties();
        properties.setEnabled(true);
        properties.setKeyStore("/etc/keystore.p12");
        properties.setKeyStorePassword("secret");
        properties.setTrustStore("/etc/truststore.p12");
        properties.setTrustStorePassword("secret");
        properties.setKeyStoreType("JKS");
        properties.setTrustStoreType("JKS");

        assertThat(properties.isEnabled()).isTrue();
        assertThat(properties.getKeyStore()).isEqualTo("/etc/keystore.p12");
        assertThat(properties.getKeyStorePassword()).isEqualTo("secret");
        assertThat(properties.getTrustStore()).isEqualTo("/etc/truststore.p12");
        assertThat(properties.getTrustStorePassword()).isEqualTo("secret");
        assertThat(properties.getKeyStoreType()).isEqualTo("JKS");
        assertThat(properties.getTrustStoreType()).isEqualTo("JKS");
    }

    @Test
    void mtlsProperties_enabledByDefaultFalse() {
        MtlsProperties properties = new MtlsProperties();
        assertThat(properties.isEnabled()).isFalse();
    }

    @Test
    void mtlsProperties_keyStoreDefaults() {
        MtlsProperties properties = new MtlsProperties();
        assertThat(properties.getKeyStore()).isNull();
        assertThat(properties.getKeyStorePassword()).isNull();
        assertThat(properties.getKeyStoreType()).isEqualTo("PKCS12");
        assertThat(properties.getTrustStore()).isNull();
        assertThat(properties.getTrustStorePassword()).isNull();
        assertThat(properties.getTrustStoreType()).isEqualTo("PKCS12");
    }
}