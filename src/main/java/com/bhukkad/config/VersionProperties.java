package com.bhukkad.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
@ConfigurationProperties(prefix = "app.api.versioning")
public class VersionProperties {
    /**
     * The current API version.
     */
    private String currentVersion = "1";

    /**
     * List of deprecated versions.
     * Requests using these versions will receive a deprecation warning.
     */
    private List<String> deprecatedVersions = List.of();

    /**
     * List of unsupported versions.
     * Requests using these versions will be rejected with 400.
     */
    private List<String> unsupportedVersions = List.of();

    public String getCurrentVersion() {
        return currentVersion;
    }

    public void setCurrentVersion(String currentVersion) {
        this.currentVersion = currentVersion;
    }

    public List<String> getDeprecatedVersions() {
        return deprecatedVersions;
    }

    public void setDeprecatedVersions(List<String> deprecatedVersions) {
        this.deprecatedVersions = deprecatedVersions;
    }

    public List<String> getUnsupportedVersions() {
        return unsupportedVersions;
    }

    public void setUnsupportedVersions(List<String> unsupportedVersions) {
        this.unsupportedVersions = unsupportedVersions;
    }
}