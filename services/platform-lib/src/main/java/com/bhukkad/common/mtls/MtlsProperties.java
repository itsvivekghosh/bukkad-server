package com.bhukkad.common.mtls;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Configuration properties for mutual TLS (mTLS) on service-to-service
 * calls and inbound server TLS. All fields default to disabled / empty so
 * existing deployments are unaffected unless the profile/env switches them on.
 */
@ConfigurationProperties(prefix = "app.mtls")
public class MtlsProperties {

    /** Master switch — when false, no TLS or mTLS is configured. */
    private boolean enabled = false;

    /** Keystore containing the service's own certificate + private key. */
    private String keyStore;

    private String keyStorePassword;

    private String keyStoreType = "PKCS12";

    /** Truststore containing the internal CA certificate(s). */
    private String trustStore;

    private String trustStorePassword;

    private String trustStoreType = "PKCS12";

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public String getKeyStore() {
        return keyStore;
    }

    public void setKeyStore(String keyStore) {
        this.keyStore = keyStore;
    }

    public String getKeyStorePassword() {
        return keyStorePassword;
    }

    public void setKeyStorePassword(String keyStorePassword) {
        this.keyStorePassword = keyStorePassword;
    }

    public String getKeyStoreType() {
        return keyStoreType;
    }

    public void setKeyStoreType(String keyStoreType) {
        this.keyStoreType = keyStoreType;
    }

    public String getTrustStore() {
        return trustStore;
    }

    public void setTrustStore(String trustStore) {
        this.trustStore = trustStore;
    }

    public String getTrustStorePassword() {
        return trustStorePassword;
    }

    public void setTrustStorePassword(String trustStorePassword) {
        this.trustStorePassword = trustStorePassword;
    }

    public String getTrustStoreType() {
        return trustStoreType;
    }

    public void setTrustStoreType(String trustStoreType) {
        this.trustStoreType = trustStoreType;
    }
}
