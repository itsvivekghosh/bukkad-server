package com.bhukkad.common.web;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.List;

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
    private List<String> unsupportedVersions = List.of("0");

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