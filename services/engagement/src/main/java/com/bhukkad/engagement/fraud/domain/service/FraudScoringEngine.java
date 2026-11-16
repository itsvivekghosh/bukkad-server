package com.bhukkad.engagement.fraud.domain.service;

import com.bhukkad.engagement.chat.domain.entity.Attachment;
import com.bhukkad.engagement.chat.domain.entity.ChatMessage;
import com.bhukkad.engagement.chat.domain.entity.ChatSession;
import com.bhukkad.engagement.chat.domain.repository.AttachmentRepository;
import com.bhukkad.engagement.fraud.config.FraudProperties;
import com.bhukkad.engagement.fraud.domain.entity.FraudFlag;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Fraud Scoring Engine - computes risk scores using multiple detection layers.
 * 
 * <p>Performance optimizations:
 * <ul>
 *   <li>Pre-compiled regex pattern for O(n) keyword detection</li>
 *   <li>Batch duplicate detection queries (1 query instead of N)</li>
 *   <li>Caffeine bounded caches with TTL eviction to prevent memory leaks</li>
 *   <li>Set-based MIME type lookups (O(1) vs O(n))</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class FraudScoringEngine {

    private final FraudProperties fraudProperties;
    private final AttachmentRepository attachmentRepository;

    // Pre-compiled regex pattern - O(n) single-pass keyword detection
    private static final Pattern SUSPICIOUS_KEYWORDS_PATTERN = Pattern.compile(
        "\\b(fake|never received|wrong order|stolen|fake photo|missing order|late delivery|scam|refund fraud)\\b",
        Pattern.CASE_INSENSITIVE
    );

    // Caffeine bounded cache for behavioral cooldown tracking (max 100K entries, 60 min TTL)
    private final Cache<Long, LocalDateTime> customerLastFraudCheck = Caffeine.newBuilder()
        .maximumSize(100_000)
        .expireAfterWrite(java.time.Duration.ofMinutes(60))
        .build();

    // Caffeine bounded cache for attachment fingerprint tracking (max 500K entries, 24h TTL)
    private final Cache<String, LocalDateTime> attachmentFingerprintCache = Caffeine.newBuilder()
        .maximumSize(500_000)
        .expireAfterWrite(java.time.Duration.ofHours(24))
        .build();

    // Atomic counter for cache eviction tracking
    private final AtomicLong cacheEvictionCounter = new AtomicLong(0);

    /**
     * Calculates the fraud risk score for a chat session with attachments.
     * Optimized with batch processing and pre-compiled patterns.
     */
    @Transactional(readOnly = true)
    public FraudDetectionEngine.FraudScore evaluateRisk(ChatSession session, ChatMessage message, 
                                                        List<Attachment> attachments) {
        double totalScore = 0.0;
        Map<String, Double> factorScores = new HashMap<>(4, 1.0f);
        List<FraudFlag> detectedFlags = new ArrayList<>(5);

        FraudProperties.FraudDetection detection = fraudProperties.getDetection();
        FraudProperties.Scoring scoring = fraudProperties.getScoring();

        // 1. Behavioral Analysis
        if (detection.isEnableBehavioralAnalysis()) {
            FraudDetectionEngine.BehavioralScore behavioral = analyzeBehavioralPatterns(session, message);
            double weightedBehavioral = (behavioral.score() * scoring.getWeightBehavioral()) / 100.0;
            totalScore += weightedBehavioral;
            factorScores.put("behavioral", weightedBehavioral);
            if (behavioral.flags().size() > 2) {
                detectedFlags.addAll(behavioral.flags());
            }
        }

        // 2. Image Metadata Analysis
        if (detection.isEnableImageAnalysis() && attachments != null && !attachments.isEmpty()) {
            FraudDetectionEngine.ImageAnalysisScore imageScore = analyzeImageMetadata(session, attachments);
            double weightedImage = (imageScore.score() * scoring.getWeightImageMetadata()) / 100.0;
            totalScore += weightedImage;
            factorScores.put("image_metadata", weightedImage);
            if (!imageScore.flags().isEmpty()) {
                detectedFlags.addAll(imageScore.flags());
            }
        }

        // 3. Claim Pattern Analysis
        if (detection.isEnableClaimPatternAnalysis()) {
            FraudDetectionEngine.ClaimPatternScore claimScore = analyzeClaimPatterns(session);
            double weightedClaim = (claimScore.score() * scoring.getWeightClaimPattern()) / 100.0;
            totalScore += weightedClaim;
            factorScores.put("claim_pattern", weightedClaim);
            if (!claimScore.flags().isEmpty()) {
                detectedFlags.addAll(claimScore.flags());
            }
        }

        // 4. Duplicate Detection - OPTIMIZED WITH BATCH QUERY
        if (detection.isEnableDuplicateDetection() && attachments != null && !attachments.isEmpty()) {
            FraudDetectionEngine.DuplicateScore duplicateScore = analyzeDuplicatePatternsOptimized(attachments);
            double weightedDuplicate = (duplicateScore.score() * scoring.getWeightDuplicateCheck()) / 100.0;
            totalScore += weightedDuplicate;
            factorScores.put("duplicate_check", weightedDuplicate);
            if (!duplicateScore.flags().isEmpty()) {
                detectedFlags.addAll(duplicateScore.flags());
            }
        }

        // Normalize the total score (capped at 100)
        double normalizedScore = Math.min(totalScore * (100.0 / scoring.getNormalizationFactor()), 100.0);

        return new FraudDetectionEngine.FraudScore(normalizedScore, factorScores, detectedFlags);
    }

    /**
     * Optimized behavioral analysis with pre-compiled patterns.
     * Replaced O(n×m) linear scan with O(n) regex pattern matching.
     */
    private FraudDetectionEngine.BehavioralScore analyzeBehavioralPatterns(ChatSession session, ChatMessage message) {
        List<FraudFlag> flags = new ArrayList<>(3);
        double score = 0.0;

        if (message != null) {
            if (session.getCustomerId() != null) {
                LocalDateTime lastCheck = customerLastFraudCheck.getIfPresent(session.getCustomerId());
                
                if (lastCheck != null) {
                    long minutesSince = ChronoUnit.MINUTES.between(lastCheck, LocalDateTime.now());
                    long cooldownMinutes = fraudProperties.getDetection().getSameCustomerCooldownMinutes();
                    
                    if (minutesSince < cooldownMinutes) {
                        score += 30.0;
                        flags.add(FraudFlag.of("CUSTOMER_COOLDOWN_VIOLATION", 
                                "Customer sent fraud check too quickly: " + minutesSince + " minutes"));
                    }
                }
                
                customerLastFraudCheck.put(session.getCustomerId(), LocalDateTime.now());
            }
        }

        // Check for suspicious keywords using pre-compiled pattern - O(n) single pass
        if (message != null && message.getContent() != null) {
            String content = message.getContent();
            long keywordCount = SUSPICIOUS_KEYWORDS_PATTERN.matcher(content).results().count();
            
            if (keywordCount >= 2) {
                score += 25.0;
                flags.add(FraudFlag.of("SUSPICIOUS_KEYWORDS", 
                        "Multiple suspicious keywords detected: " + keywordCount));
            }
        }

        return new FraudDetectionEngine.BehavioralScore(Math.min(score, 100.0), flags);
    }

    /**
     * Analyze image metadata for fraud indicators.
     */
    private FraudDetectionEngine.ImageAnalysisScore analyzeImageMetadata(ChatSession session, List<Attachment> attachments) {
        double score = 0.0;
        List<FraudFlag> flags = new ArrayList<>(4);

        for (Attachment attachment : attachments) {
            // Check for missing or suspicious EXIF data
            if (attachment.getExifDatetimeOriginal() == null) {
                score += 20.0;
                flags.add(FraudFlag.of("MISSING_EXIF", 
                        "Image missing EXIF timestamp - possible manipulation"));
            }

            // Check GPS coordinates for implausible locations
            Double lat = attachment.getGpsLatitude();
            Double lon = attachment.getGpsLongitude();
            if (lat != null && lon != null) {
                if (Math.abs(lat) > 90 || Math.abs(lon) > 180) {
                    score += 40.0;
                    flags.add(FraudFlag.of("INVALID_GPS", "Invalid GPS coordinates detected"));
                }
            }

            // Check image dimensions vs file size ratio
            Integer imgWidth = attachment.getImageWidth();
            Integer imgHeight = attachment.getImageHeight();
            Long fileSize = attachment.getFileSizeBytes();
            
            if (imgWidth != null && imgHeight != null && fileSize != null && fileSize > 0) {
                long expectedSize = (long) imgWidth * imgHeight * 3L;
                double ratio = (double) fileSize / expectedSize;
                
                if (ratio < 0.1) {
                    score += 25.0;
                    flags.add(FraudFlag.of("HIGH_COMPRESSION", "Image highly compressed - possible editing"));
                }
            }

            // Check for device spoofing indicators
            String make = attachment.getExifMake();
            if (make == null || make.isEmpty()) {
                score += 15.0;
                flags.add(FraudFlag.of("MISSING_DEVICE_INFO", "Image missing device manufacturer info"));
            }
        }

        return new FraudDetectionEngine.ImageAnalysisScore(Math.min(score, 100.0), flags);
    }

    /**
     * Optimized claim pattern analysis.
     */
    private FraudDetectionEngine.ClaimPatternScore analyzeClaimPatterns(ChatSession session) {
        double score = 0.0;
        List<FraudFlag> flags = new ArrayList<>(2);

        // Check if this is a pattern of similar claims
        LocalDateTime threeDaysAgo = LocalDateTime.now().minusDays(3);
        // TODO: Replace with repository query for high-risk claims count
        // Using placeholder - will be replaced by dedicated repository method

        ChatSession.Department dept = session.getDepartment();
        if (dept == ChatSession.Department.ORDER || dept == ChatSession.Department.DELIVERY) {
            int hourOfDay = LocalDateTime.now().getHour();
            if (hourOfDay >= 0 && hourOfDay <= 4) {
                score += 20.0;
                flags.add(FraudFlag.of("LATE_NIGHT_CLAIM", 
                        "Claim filed during unusual hours: " + hourOfDay + ":00"));
            }
        }

        return new FraudDetectionEngine.ClaimPatternScore(Math.min(score, 100.0), flags);
    }

    /**
     * OPTIMIZED duplicate detection using batch query.
     * 
     * <p>Before: N individual database queries for N attachments (O(n) DB calls)
     * <p>After: 1 batch query + O(1) HashMap lookups (1 DB call)
     */
    private FraudDetectionEngine.DuplicateScore analyzeDuplicatePatternsOptimized(List<Attachment> attachments) {
        double score = 0.0;
        List<FraudFlag> flags = new ArrayList<>(2);

        // Extract all hashes for batch query
        Set<String> hashes = attachments.stream()
                .map(Attachment::getImageHash)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());

        if (hashes.isEmpty()) {
            return new FraudDetectionEngine.DuplicateScore(score, flags);
        }

        // Single batch query instead of N individual queries
        List<Attachment> existing = attachmentRepository.findByImageHashInAndAnalyzedTrue(new ArrayList<>(hashes));
        
        // Build lookup map for O(1) access
        Map<String, List<Attachment>> existingByHash = existing.stream()
                .filter(a -> a.getImageHash() != null)
                .collect(Collectors.groupingBy(Attachment::getImageHash));

        // Check each attachment against existing
        for (Attachment attachment : attachments) {
            String hash = attachment.getImageHash();
            if (hash != null) {
                List<Attachment> duplicates = existingByHash.get(hash);
                if (duplicates != null && !duplicates.isEmpty()) {
                    score += 35.0;
                    Attachment existingOne = duplicates.get(0);
                    flags.add(FraudFlag.of("DUPLICATE_ATTACHMENT", 
                            "Same image used in previous case: " + existingOne.getAttachmentId()));
                }
            }
        }

        // Cache the fingerprint for cooldown check
        if (!hashes.isEmpty()) {
            String firstHash = attachments.get(0).getImageHash();
            if (firstHash != null) {
                attachmentFingerprintCache.put(firstHash, LocalDateTime.now());
            }
        }

        return new FraudDetectionEngine.DuplicateScore(score, flags);
    }
}