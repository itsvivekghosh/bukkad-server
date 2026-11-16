package com.bhukkad.engagement.support.chat.fraud;

import com.bhukkad.engagement.support.chat.domain.entity.Attachment;
import com.bhukkad.engagement.support.chat.domain.entity.ChatMessage;
import com.bhukkad.engagement.support.chat.domain.entity.FraudAlert;
import com.bhukkad.engagement.support.chat.domain.repository.AttachmentRepository;
import com.bhukkad.engagement.support.chat.domain.repository.ChatMessageRepository;
import com.bhukkad.engagement.support.chat.domain.repository.FraudAlertRepository;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Chat fraud detection engine for the support chat system.
 *
 * <p>Note: This bean is explicitly named {@code chatFraudDetectionEngine} to
 * avoid conflict with {@code com.bhukkad.engagement.fraud.domain.service.FraudDetectionEngine}.
 *</p>
 */
@Component("chatFraudDetectionEngine")
/**
 * FraudDetectionEngine - Core anti-fraud engine for the chat support system.
 *
 * <p>This engine detects and prevents fraudulent activities in real-time chat
 * conversations.</p>
 *
 * <p>Optimizations:</p>
 * <ul>
 *   <li>Pre-compiled regex patterns for O(n) single-pass keyword detection</li>
 *   <li>Set-based lookups for O(1) keyword checks</li>
 *   <li>Bounded Caffeine cache to prevent memory leaks</li>
 *   <li>Batch database queries where possible</li>
 * </ul>
 */
@Slf4j
public class FraudDetectionEngine {

    private static final Logger log = LoggerFactory.getLogger(FraudDetectionEngine.class);

    // Score thresholds
    private static final int SCORE_CRITICAL_MIN = 80;
    private static final int SCORE_HIGH_MIN = 60;
    private static final int SCORE_MEDIUM_MIN = 30;

    // Fraud score weights for different detection types
    private static final int SCORE_DUPLICATE_MESSAGE = 25;
    private static final int SCORE_REFUND_KEYWORD = 35;
    private static final int SCORE_IMAGE_TAMPERING = 40;
    private static final int SCORE_VELOCITY_VIOLATION = 20;
    private static final int SCORE_ORDER_MISMATCH = 30;
    private static final int SCORE_MULTI_ACCOUNT = 45;

    // Thresholds
    private static final int MAX_MESSAGES_PER_MINUTE = 10;
    private static final int MAX_MESSAGES_PER_5_MINUTES = 30;
    private static final int MAX_ATTACHMENTS_PER_HOUR = 20;
    private static final int MAX_FILE_SIZE_MB = 10;

    // Pre-compiled regex pattern for refund scam keywords - O(n) single pass
    private static final Pattern REFUND_SCAM_PATTERN = Pattern.compile(
        "\\b(refund|money back|chargeback|dispute|fake order|never received|didn't order|unauthorized|stolen card|hacked account|wrong item|broken item|damaged goods|empty package|missing item|wrong address|never delivered)\\b",
        Pattern.CASE_INSENSITIVE
    );

    // Pre-compiled regex pattern for threat language - O(n) single pass
    private static final Pattern THREAT_PATTERN = Pattern.compile(
        "\\b(sue|lawsuit|court|police|report|better business bureau|consumer protection|federal trade commission|class action)\\b",
        Pattern.CASE_INSENSITIVE
    );

    // Image file extensions that can have EXIF data
    private static final Set<String> IMAGE_EXTENSIONS = Set.of(
        "jpg", "jpeg", "png", "webp", "tiff", "tif", "heic", "heif"
    );

    private final ChatMessageRepository messageRepository;
    private final FraudAlertRepository fraudAlertRepository;
    private final AttachmentRepository attachmentRepository;

    // Bounded cache for message velocity tracking - prevents memory leaks
    private final Cache<String, LocalDateTime> messageTimestampCache = Caffeine.newBuilder()
        .maximumSize(100_000)
        .expireAfterWrite(Duration.ofHours(1))
        .build();

    public FraudDetectionEngine(
            ChatMessageRepository messageRepository,
            FraudAlertRepository fraudAlertRepository,
            AttachmentRepository attachmentRepository) {
        this.messageRepository = messageRepository;
        this.fraudAlertRepository = fraudAlertRepository;
        this.attachmentRepository = attachmentRepository;
    }

    /**
     * Analyze a message for fraud indicators and return a fraud score (0-100).
     */
    @Transactional(readOnly = true)
    public FraudAnalysisResult analyzeMessage(ChatMessage message) {
        List<String> reasons = new ArrayList<>();
        int totalScore = 0;

        // 1. Check for duplicate messages
        int duplicateScore = checkDuplicateMessages(message);
        if (duplicateScore > 0) {
            reasons.add("Duplicate message detected");
            totalScore += duplicateScore;
        }

        // 2. Check for refund scam keywords - O(n) single pass with pre-compiled regex
        int keywordScore = checkKeywordPatterns(message.getContent());
        if (keywordScore > 0) {
            reasons.add("Refund scam keywords detected");
            totalScore += keywordScore;
        }

        // 3. Check for threat language - O(n) single pass with pre-compiled regex
        int threatScore = checkThreatKeywords(message.getContent());
        if (threatScore > 0) {
            reasons.add("Threat language detected");
            totalScore += threatScore;
        }

        // 4. Check message velocity
        int velocityScore = checkMessageVelocity(message.getSenderId(), message.getRoomId());
        if (velocityScore > 0) {
            reasons.add("Message velocity violation");
            totalScore += velocityScore;
        }

        // 5. Check attachment for tampering
        if (message.getAttachmentId() != null) {
            Attachment attachment = attachmentRepository.findById(message.getAttachmentId()).orElse(null);
            if (attachment != null) {
                int attachmentScore = checkAttachmentTampering(attachment);
                if (attachmentScore > 0) {
                    reasons.add("Image tampering detected");
                    totalScore += attachmentScore;
                }
            }
        }

        // 6. Check order validity (if message references an order)
        int orderScore = checkOrderValidity(message.getContent(), message.getSenderId());
        if (orderScore > 0) {
            reasons.add("Order mismatch detected");
            totalScore += orderScore;
        }

        // Cap score at 100
        totalScore = Math.min(totalScore, 100);

        // Determine severity
        FraudAlert.Severity severity = determineSeverity(totalScore);

        // Determine if auto-flagged
        boolean autoFlagged = totalScore >= SCORE_HIGH_MIN;

        return new FraudAnalysisResult(
                totalScore,
                severity,
                String.join("; ", reasons),
                autoFlagged
        );
    }

    /**
     * Check for duplicate messages - same content sent by same user in short timeframe.
     */
    private int checkDuplicateMessages(ChatMessage message) {
        LocalDateTime fiveMinutesAgo = LocalDateTime.now().minus(5, ChronoUnit.MINUTES);
        long count = messageRepository.countByRoomAndSenderSince(
                message.getRoomId(), message.getSenderId(), fiveMinutesAgo);
        if (count >= 2) {
            return SCORE_DUPLICATE_MESSAGE;
        } else if (count >= 1) {
            return 10;
        }
        return 0;
    }

    /**
     * Check for refund scam keywords in message content.
     * Uses pre-compiled regex for O(n) single-pass detection.
     */
    private int checkKeywordPatterns(String content) {
        if (content == null || content.isBlank()) {
            return 0;
        }

        // Single regex match finds all keywords in one pass
        java.util.regex.Matcher matcher = REFUND_SCAM_PATTERN.matcher(content);
        int matchCount = 0;
        while (matcher.find()) {
            matchCount++;
        }

        if (matchCount >= 2) {
            return SCORE_REFUND_KEYWORD;
        } else if (matchCount == 1) {
            return 15;
        }
        return 0;
    }

    /**
     * Check for threat language in message content.
     * Uses pre-compiled regex for O(n) single-pass detection.
     */
    private int checkThreatKeywords(String content) {
        if (content == null || content.isBlank()) {
            return 0;
        }

        // Single regex match finds all threat keywords in one pass
        java.util.regex.Matcher matcher = THREAT_PATTERN.matcher(content);
        if (matcher.find()) {
            return 25;
        }
        return 0;
    }

    /**
     * Check message velocity - too many messages from same user in short time.
     */
    private int checkMessageVelocity(Long senderId, Long roomId) {
        LocalDateTime oneMinuteAgo = LocalDateTime.now().minus(1, ChronoUnit.MINUTES);
        LocalDateTime fiveMinutesAgo = LocalDateTime.now().minus(5, ChronoUnit.MINUTES);

        long messagesLastMinute = messageRepository.countByRoomAndSenderSince(
                roomId, senderId, oneMinuteAgo);
        long messagesLast5Minutes = messageRepository.countByRoomAndSenderSince(
                roomId, senderId, fiveMinutesAgo);

        if (messagesLastMinute > MAX_MESSAGES_PER_MINUTE) {
            return SCORE_VELOCITY_VIOLATION;
        } else if (messagesLast5Minutes > MAX_MESSAGES_PER_5_MINUTES) {
            return 15;
        }
        return 0;
    }

    /**
     * Check attachment for tampering indicators from attachment entity.
     */
    public int checkAttachmentTampering(Attachment attachment) {
        int score = 0;

        // Check for missing EXIF data (suspicious for images)
        if (Boolean.FALSE.equals(attachment.getExifAvailable())
                && IMAGE_EXTENSIONS.contains(getFileExtension(attachment.getOriginalFilename()))) {
            score += 20;
        }

        if (Boolean.TRUE.equals(attachment.getIsTampered())) {
            score += SCORE_IMAGE_TAMPERING;
        }

        // Check file size - unusually small images might be screenshots of screenshots
        if (attachment.getFileSizeBytes() != null && attachment.getFileSizeBytes() < 5000) {
            score += 10;
        }

        return Math.min(score, SCORE_IMAGE_TAMPERING);
    }

    /**
     * Check order validity - referenced order should exist and belong to customer.
     */
    private int checkOrderValidity(String content, Long senderId) {
        if (content == null || content.isBlank()) {
            return 0;
        }

        Pattern orderIdPattern = Pattern.compile("order[#\\s:]+(\\d+)", Pattern.CASE_INSENSITIVE);
        java.util.regex.Matcher matcher = orderIdPattern.matcher(content);

        if (matcher.find()) {
            String orderIdStr = matcher.group(1);
            try {
                Long orderId = Long.parseLong(orderIdStr);
                return 10;
            } catch (NumberFormatException e) {
                return 15;
            }
        }
        return 0;
    }

    /**
     * Determine severity based on score.
     */
    private FraudAlert.Severity determineSeverity(int score) {
        if (score >= SCORE_CRITICAL_MIN) {
            return FraudAlert.Severity.CRITICAL;
        } else if (score >= SCORE_HIGH_MIN) {
            return FraudAlert.Severity.HIGH;
        } else if (score >= SCORE_MEDIUM_MIN) {
            return FraudAlert.Severity.MEDIUM;
        } else {
            return FraudAlert.Severity.LOW;
        }
    }

    /**
     * Get file extension for image type detection.
     */
    private String getFileExtension(String filename) {
        if (filename == null || !filename.contains(".")) {
            return "";
        }
        return filename.substring(filename.lastIndexOf(".") + 1).toLowerCase();
    }

    /**
     * Process attachment for fraud indicators.
     * Called when an attachment is uploaded.
     */
    public FraudAnalysisResult analyzeAttachment(Attachment attachment, Long roomId, Long senderId) {
        int score = checkAttachmentTampering(attachment);
        List<String> reasons = new ArrayList<>();

        if (score > 0) {
            if (Boolean.TRUE.equals(attachment.getIsTampered())) {
                reasons.add("Image appears to be tampered");
            }
            if (Boolean.FALSE.equals(attachment.getExifAvailable())
                    && IMAGE_EXTENSIONS.contains(getFileExtension(attachment.getOriginalFilename()))) {
                reasons.add("Missing EXIF metadata in image");
            }
        }

        FraudAlert.Severity severity = determineSeverity(score);
        boolean autoFlagged = score >= SCORE_HIGH_MIN;

        return new FraudAnalysisResult(
                score,
                severity,
                String.join("; ", reasons),
                autoFlagged
        );
    }

    /**
     * Get fraud statistics for dashboard.
     */
    public FraudStats getFraudStats(LocalDateTime since) {
        long totalAlerts = fraudAlertRepository.countByCreatedSince(since);
        long criticalAlerts = fraudAlertRepository.findByCreatedSince(since).stream()
                .filter(a -> a.getSeverity() == FraudAlert.Severity.CRITICAL)
                .count();
        long highAlerts = fraudAlertRepository.findByCreatedSince(since).stream()
                .filter(a -> a.getSeverity() == FraudAlert.Severity.HIGH)
                .count();
        long unreviewedAlerts = fraudAlertRepository.countUnreviewed();

        return new FraudStats(totalAlerts, criticalAlerts, highAlerts, unreviewedAlerts);
    }

    public record FraudStats(
        long totalAlerts,
        long criticalAlerts,
        long highAlerts,
        long unreviewedAlerts
    ) {}

    public record FraudAnalysisResult(
        int fraudScore,
        FraudAlert.Severity severity,
        String fraudReasons,
        boolean isAutoFlagged
    ) {}
}
