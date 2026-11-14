package com.bhukkad.engagement.fraud.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Configuration properties for the Anti-Fraud Engine.
 * Configures thresholds and scoring weights for various fraud detection rules.
 */
@ConfigurationProperties(prefix = "app.fraud")
public class FraudProperties {

    private FraudDetection detection = new FraudDetection();
    private Scoring scoring = new Scoring();
    private Attachments attachments = new Attachments();
    private Notifications notifications = new Notifications();

    public FraudDetection getDetection() {
        return detection;
    }

    public void setDetection(FraudDetection detection) {
        this.detection = detection;
    }

    public Scoring getScoring() {
        return scoring;
    }

    public void setScoring(Scoring scoring) {
        this.scoring = scoring;
    }

    public Attachments getAttachments() {
        return attachments;
    }

    public void setAttachments(Attachments attachments) {
        this.attachments = attachments;
    }

    public Notifications getNotifications() {
        return notifications;
    }

    public void setNotifications(Notifications notifications) {
        this.notifications = notifications;
    }

    public FraudProperties() {}

    public FraudProperties(FraudDetection detection, Scoring scoring, 
                           Attachments attachments, Notifications notifications) {
        this.detection = detection;
        this.scoring = scoring;
        this.attachments = attachments;
        this.notifications = notifications;
    }

    public static class FraudDetection {
        private double defaultRiskThreshold = 50.0;
        private double highRiskThreshold = 75.0;
        private double criticalRiskThreshold = 90.0;
        private boolean enableBehavioralAnalysis = true;
        private boolean enableImageAnalysis = true;
        private boolean enableClaimPatternAnalysis = true;
        private boolean enableDuplicateDetection = true;
        private int sameCustomerCooldownMinutes = 60;
        private int sameAttachmentCooldownMinutes = 1440;

        public double getDefaultRiskThreshold() { return defaultRiskThreshold; }
        public void setDefaultRiskThreshold(double defaultRiskThreshold) { this.defaultRiskThreshold = defaultRiskThreshold; }

        public double getHighRiskThreshold() { return highRiskThreshold; }
        public void setHighRiskThreshold(double highRiskThreshold) { this.highRiskThreshold = highRiskThreshold; }

        public double getCriticalRiskThreshold() { return criticalRiskThreshold; }
        public void setCriticalRiskThreshold(double criticalRiskThreshold) { this.criticalRiskThreshold = criticalRiskThreshold; }

        public boolean isEnableBehavioralAnalysis() { return enableBehavioralAnalysis; }
        public void setEnableBehavioralAnalysis(boolean enableBehavioralAnalysis) { this.enableBehavioralAnalysis = enableBehavioralAnalysis; }

        public boolean isEnableImageAnalysis() { return enableImageAnalysis; }
        public void setEnableImageAnalysis(boolean enableImageAnalysis) { this.enableImageAnalysis = enableImageAnalysis; }

        public boolean isEnableClaimPatternAnalysis() { return enableClaimPatternAnalysis; }
        public void setEnableClaimPatternAnalysis(boolean enableClaimPatternAnalysis) { this.enableClaimPatternAnalysis = enableClaimPatternAnalysis; }

        public boolean isEnableDuplicateDetection() { return enableDuplicateDetection; }
        public void setEnableDuplicateDetection(boolean enableDuplicateDetection) { this.enableDuplicateDetection = enableDuplicateDetection; }

        public int getSameCustomerCooldownMinutes() { return sameCustomerCooldownMinutes; }
        public void setSameCustomerCooldownMinutes(int sameCustomerCooldownMinutes) { this.sameCustomerCooldownMinutes = sameCustomerCooldownMinutes; }

        public int getSameAttachmentCooldownMinutes() { return sameAttachmentCooldownMinutes; }
        public void setSameAttachmentCooldownMinutes(int sameAttachmentCooldownMinutes) { this.sameAttachmentCooldownMinutes = sameAttachmentCooldownMinutes; }
    }

    public static class Scoring {
        private double weightBehavioral = 30.0;
        private double weightImageMetadata = 25.0;
        private double weightClaimPattern = 30.0;
        private double weightDuplicateCheck = 35.0;
        private double weightTimingAnomaly = 20.0;
        private double weightFrequency = 15.0;
        private double normalizationFactor = 100.0;

        public double getWeightBehavioral() { return weightBehavioral; }
        public void setWeightBehavioral(double weightBehavioral) { this.weightBehavioral = weightBehavioral; }

        public double getWeightImageMetadata() { return weightImageMetadata; }
        public void setWeightImageMetadata(double weightImageMetadata) { this.weightImageMetadata = weightImageMetadata; }

        public double getWeightClaimPattern() { return weightClaimPattern; }
        public void setWeightClaimPattern(double weightClaimPattern) { this.weightClaimPattern = weightClaimPattern; }

        public double getWeightDuplicateCheck() { return weightDuplicateCheck; }
        public void setWeightDuplicateCheck(double weightDuplicateCheck) { this.weightDuplicateCheck = weightDuplicateCheck; }

        public double getWeightTimingAnomaly() { return weightTimingAnomaly; }
        public void setWeightTimingAnomaly(double weightTimingAnomaly) { this.weightTimingAnomaly = weightTimingAnomaly; }

        public double getWeightFrequency() { return weightFrequency; }
        public void setWeightFrequency(double weightFrequency) { this.weightFrequency = weightFrequency; }

        public double getNormalizationFactor() { return normalizationFactor; }
        public void setNormalizationFactor(double normalizationFactor) { this.normalizationFactor = normalizationFactor; }
    }

    public static class Attachments {
        private long maxFileSizeBytes = 10L * 1024 * 1024;
        private String[] allowedMimeTypes = {"image/jpeg", "image/png", "image/webp", "application/pdf"};
        private int maxImageDimension = 4000;
        private boolean analyzeExif = true;
        private boolean checkImageIntegrity = true;

        public long getMaxFileSizeBytes() { return maxFileSizeBytes; }
        public void setMaxFileSizeBytes(long maxFileSizeBytes) { this.maxFileSizeBytes = maxFileSizeBytes; }

        public String[] getAllowedMimeTypes() { return allowedMimeTypes; }
        public void setAllowedMimeTypes(String[] allowedMimeTypes) { this.allowedMimeTypes = allowedMimeTypes; }

        public int getMaxImageDimension() { return maxImageDimension; }
        public void setMaxImageDimension(int maxImageDimension) { this.maxImageDimension = maxImageDimension; }

        public boolean isAnalyzeExif() { return analyzeExif; }
        public void setAnalyzeExif(boolean analyzeExif) { this.analyzeExif = analyzeExif; }

        public boolean isCheckImageIntegrity() { return checkImageIntegrity; }
        public void setCheckImageIntegrity(boolean checkImageIntegrity) { this.checkImageIntegrity = checkImageIntegrity; }
    }

    public static class Notifications {
        private boolean notifyAdmins = true;
        private boolean notifySupport = true;
        private int autoEscalateAfterMinutes = 60;

        public boolean isNotifyAdmins() { return notifyAdmins; }
        public void setNotifyAdmins(boolean notifyAdmins) { this.notifyAdmins = notifyAdmins; }

        public boolean isNotifySupport() { return notifySupport; }
        public void setNotifySupport(boolean notifySupport) { this.notifySupport = notifySupport; }

        public int getAutoEscalateAfterMinutes() { return autoEscalateAfterMinutes; }
        public void setAutoEscalateAfterMinutes(int autoEscalateAfterMinutes) { this.autoEscalateAfterMinutes = autoEscalateAfterMinutes; }
    }

    // Cache configuration for in-memory caches
    private CacheConfig cache = new CacheConfig();

    public CacheConfig getCache() { return cache; }
    public void setCache(CacheConfig cache) { this.cache = cache; }

    public static class CacheConfig {
        private int customerCooldownCacheSize = 100_000;
        private int attachmentCacheSize = 500_000;
        private int customerCooldownTtlMinutes = 60;
        private int attachmentCooldownTtlMinutes = 1440;

        public int getCustomerCooldownCacheSize() { return customerCooldownCacheSize; }
        public void setCustomerCooldownCacheSize(int customerCooldownCacheSize) { this.customerCooldownCacheSize = customerCooldownCacheSize; }

        public int getAttachmentCacheSize() { return attachmentCacheSize; }
        public void setAttachmentCacheSize(int attachmentCacheSize) { this.attachmentCacheSize = attachmentCacheSize; }

        public int getCustomerCooldownTtlMinutes() { return customerCooldownTtlMinutes; }
        public void setCustomerCooldownTtlMinutes(int customerCooldownTtlMinutes) { this.customerCooldownTtlMinutes = customerCooldownTtlMinutes; }

        public int getAttachmentCooldownTtlMinutes() { return attachmentCooldownTtlMinutes; }
        public void setAttachmentCooldownTtlMinutes(int attachmentCooldownTtlMinutes) { this.attachmentCooldownTtlMinutes = attachmentCooldownTtlMinutes; }
    }

    /**
     * Configuration wrapper for API responses.
     */
    public static class Configuration {
        private double defaultRiskThreshold;
        private double highRiskThreshold;
        private double criticalRiskThreshold;
        private boolean enableBehavioralAnalysis;
        private boolean enableImageAnalysis;
        private boolean enableClaimPatternAnalysis;
        private boolean enableDuplicateDetection;
        private int sameCustomerCooldownMinutes;
        private int sameAttachmentCooldownMinutes;

        public Configuration() {
            this(50.0, 75.0, 90.0, true, true, true, true, 60, 1440);
        }

        public Configuration(double defaultRiskThreshold, double highRiskThreshold, 
                             double criticalRiskThreshold, boolean enableBehavioralAnalysis,
                             boolean enableImageAnalysis, boolean enableClaimPatternAnalysis,
                             boolean enableDuplicateDetection, int sameCustomerCooldownMinutes,
                             int sameAttachmentCooldownMinutes) {
            this.defaultRiskThreshold = defaultRiskThreshold;
            this.highRiskThreshold = highRiskThreshold;
            this.criticalRiskThreshold = criticalRiskThreshold;
            this.enableBehavioralAnalysis = enableBehavioralAnalysis;
            this.enableImageAnalysis = enableImageAnalysis;
            this.enableClaimPatternAnalysis = enableClaimPatternAnalysis;
            this.enableDuplicateDetection = enableDuplicateDetection;
            this.sameCustomerCooldownMinutes = sameCustomerCooldownMinutes;
            this.sameAttachmentCooldownMinutes = sameAttachmentCooldownMinutes;
        }

        public Configuration(FraudProperties props) {
            this(
                props.getDetection().getDefaultRiskThreshold(),
                props.getDetection().getHighRiskThreshold(),
                props.getDetection().getCriticalRiskThreshold(),
                props.getDetection().isEnableBehavioralAnalysis(),
                props.getDetection().isEnableImageAnalysis(),
                props.getDetection().isEnableClaimPatternAnalysis(),
                props.getDetection().isEnableDuplicateDetection(),
                props.getDetection().getSameCustomerCooldownMinutes(),
                props.getDetection().getSameAttachmentCooldownMinutes()
            );
        }

        public double getDefaultRiskThreshold() { return defaultRiskThreshold; }
        public double getHighRiskThreshold() { return highRiskThreshold; }
        public double getCriticalRiskThreshold() { return criticalRiskThreshold; }
        public boolean isEnableBehavioralAnalysis() { return enableBehavioralAnalysis; }
        public boolean isEnableImageAnalysis() { return enableImageAnalysis; }
        public boolean isEnableClaimPatternAnalysis() { return enableClaimPatternAnalysis; }
        public boolean isEnableDuplicateDetection() { return enableDuplicateDetection; }
        public int getSameCustomerCooldownMinutes() { return sameCustomerCooldownMinutes; }
        public int getSameAttachmentCooldownMinutes() { return sameAttachmentCooldownMinutes; }
    }
}