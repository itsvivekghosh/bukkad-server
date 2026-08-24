package com.bhukkad.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;

@Component
@ConfigurationProperties(prefix = "app.degradation")
public class DegradationProperties {
    private Map<String, Boolean> features = new HashMap<>();

    public Map<String, Boolean> getFeatures() {
        return features;
    }

    public void setFeatures(Map<String, Boolean> features) {
        this.features = features != null ? features : new HashMap<>();
    }

    public boolean isEnabled(String feature) {
        Boolean enabled = features.getOrDefault(feature, true);
        return enabled != null && enabled;
    }
}