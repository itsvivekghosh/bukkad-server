package com.bhukkad.engagement.fraud.domain.service;

import com.bhukkad.engagement.chat.domain.entity.Attachment;
import com.bhukkad.engagement.chat.domain.entity.ChatMessage;
import com.bhukkad.engagement.chat.domain.entity.ChatSession;
import com.bhukkad.engagement.fraud.config.FraudProperties;
import com.bhukkad.engagement.fraud.domain.entity.FraudCase;
import com.bhukkad.engagement.fraud.domain.repository.FraudCaseRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Collections;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class FraudDetectionEngineTest {

    @Mock
    private FraudScoringEngine scoringEngine;

    @Mock
    private AttachmentValidator attachmentValidator;

    @Mock
    private FraudCaseRepository fraudCaseRepository;

    private final ObjectMapper objectMapper = new ObjectMapper();
    private FraudProperties fraudProperties;

    private FraudDetectionEngine fraudEngine;

    @BeforeEach
    void setUp() {
        fraudProperties = new FraudProperties();
        fraudEngine = new FraudDetectionEngine(
            fraudProperties, 
            scoringEngine, 
            attachmentValidator, 
            fraudCaseRepository, 
            null, // ApplicationEventPublisher - not needed for tests
            objectMapper
        );
    }

    @Test
    @DisplayName("Should return low risk score for normal session without red flags")
    void shouldReturnLowRiskForNormalSession() {
        ChatSession session = ChatSession.builder()
                .sessionId(java.util.UUID.randomUUID())
                .customerId(1L)
                .department(ChatSession.Department.GENERAL)
                .status(ChatSession.Status.OPEN)
                .priority(ChatSession.Priority.NORMAL)
                .createdAt(LocalDateTime.now().minusHours(1))
                .build();

        ChatMessage message = ChatMessage.builder()
                .messageId(java.util.UUID.randomUUID())
                .sessionId(session.getSessionId())
                .senderId(1L)
                .senderRole(ChatMessage.SenderRole.CUSTOMER)
                .messageType(ChatMessage.MessageType.TEXT)
                .content("I have a question about my order")
                .createdAt(LocalDateTime.now())
                .build();

        FraudDetectionEngine.FraudScore mockScore = new FraudDetectionEngine.FraudScore(
            15.0,
            Collections.singletonMap("behavioral", 5.0),
            List.of()
        );
        when(scoringEngine.evaluateRisk(session, message, Collections.emptyList())).thenReturn(mockScore);

        FraudDetectionEngine.FraudScore score = fraudEngine.evaluateRisk(session, message, Collections.emptyList());

        assertThat(score.totalScore()).isLessThan(30.0);
    }

    @Test
    @DisplayName("Should detect suspicious keywords in message content")
    void shouldDetectSuspiciousKeywords() {
        ChatSession session = ChatSession.builder()
                .sessionId(java.util.UUID.randomUUID())
                .customerId(1L)
                .department(ChatSession.Department.ORDER)
                .status(ChatSession.Status.OPEN)
                .createdAt(LocalDateTime.now())
                .build();

        ChatMessage message = ChatMessage.builder()
                .messageId(java.util.UUID.randomUUID())
                .sessionId(session.getSessionId())
                .senderId(1L)
                .senderRole(ChatMessage.SenderRole.CUSTOMER)
                .messageType(ChatMessage.MessageType.TEXT)
                .content("I never received my order, it was the wrong order, please refund")
                .createdAt(LocalDateTime.now())
                .build();

        FraudDetectionEngine.FraudScore mockScore = new FraudDetectionEngine.FraudScore(
            25.0,
            Collections.singletonMap("behavioral", 25.0),
            List.of()
        );
        when(scoringEngine.evaluateRisk(session, message, Collections.emptyList())).thenReturn(mockScore);

        FraudDetectionEngine.FraudScore score = fraudEngine.evaluateRisk(session, message, Collections.emptyList());

        assertThat(score.factorScores()).containsKey("behavioral");
    }

    @Test
    @DisplayName("Should detect high risk for attachments with missing EXIF data")
    void shouldDetectHighRiskForMissingExif() {
        ChatSession session = ChatSession.builder()
                .sessionId(java.util.UUID.randomUUID())
                .customerId(1L)
                .department(ChatSession.Department.DELIVERY)
                .status(ChatSession.Status.OPEN)
                .createdAt(LocalDateTime.now())
                .build();

        ChatMessage message = ChatMessage.builder()
                .messageId(java.util.UUID.randomUUID())
                .sessionId(session.getSessionId())
                .senderId(1L)
                .senderRole(ChatMessage.SenderRole.CUSTOMER)
                .messageType(ChatMessage.MessageType.IMAGE)
                .content("Photo of wrong order")
                .createdAt(LocalDateTime.now())
                .build();

        Attachment attachment = Attachment.builder()
                .attachmentId(java.util.UUID.randomUUID())
                .messageId(message.getMessageId())
                .fileName("order_wrong.jpg")
                .fileSizeBytes(500000L)
                .mimeType("image/jpeg")
                .imageWidth(1920)
                .imageHeight(1080)
                .exifDatetimeOriginal(null)
                .exifMake(null)
                .analysisStatus(Attachment.AnalysisStatus.PENDING)
                .createdAt(LocalDateTime.now())
                .build();

        List<Attachment> attachments = List.of(attachment);
        FraudDetectionEngine.FraudScore mockScore = new FraudDetectionEngine.FraudScore(
            35.0,
            Collections.singletonMap("image_metadata", 35.0),
            List.of()
        );
        when(scoringEngine.evaluateRisk(session, message, attachments)).thenReturn(mockScore);

        FraudDetectionEngine.FraudScore score = fraudEngine.evaluateRisk(session, message, attachments);

        assertThat(score.factorScores()).containsKey("image_metadata");
        assertThat(score.totalScore()).isGreaterThan(5.0);
    }

    @Test
    @DisplayName("Should create fraud case when risk exceeds threshold")
    void shouldCreateFraudCaseAboveThreshold() {
        ChatSession session = ChatSession.builder()
                .sessionId(java.util.UUID.randomUUID())
                .customerId(1L)
                .department(ChatSession.Department.ORDER)
                .status(ChatSession.Status.OPEN)
                .createdAt(LocalDateTime.now())
                .build();

        ChatMessage message = ChatMessage.builder()
                .messageId(java.util.UUID.randomUUID())
                .sessionId(session.getSessionId())
                .senderId(1L)
                .senderRole(ChatMessage.SenderRole.CUSTOMER)
                .messageType(ChatMessage.MessageType.TEXT)
                .content("Never received my order, want full refund")
                .createdAt(LocalDateTime.now())
                .build();

        FraudCase savedFraudCase = FraudCase.builder()
                .caseId(java.util.UUID.randomUUID())
                .sessionId(session.getSessionId())
                .messageId(message.getMessageId())
                .fraudType(FraudCase.FraudType.FAKE_ORDER_CLAIM)
                .riskScore(BigDecimal.valueOf(85.0))
                .confidenceLevel(FraudCase.ConfidenceLevel.HIGH)
                .status(FraudCase.Status.DETECTED)
                .detectionMethod("behavioral, claim_pattern")
                .createdAt(LocalDateTime.now())
                .build();

        when(fraudCaseRepository.save(any(FraudCase.class))).thenReturn(savedFraudCase);

        FraudDetectionEngine.FraudScore testScore = new FraudDetectionEngine.FraudScore(
                85.0,
                Collections.singletonMap("behavioral", 30.0),
                List.of()
        );

        FraudCase result = fraudEngine.createFraudCase(session, message, null, testScore);

        assertThat(result).isNotNull();
        assertThat(result.getRiskScore()).isEqualByComparingTo(BigDecimal.valueOf(85.0));
        assertThat(result.getFraudType()).isEqualTo(FraudCase.FraudType.FAKE_ORDER_CLAIM);
        verify(fraudCaseRepository).save(any(FraudCase.class));
    }

    @Test
    @DisplayName("Should not create fraud case when risk is below threshold")
    void shouldNotCreateFraudCaseBelowThreshold() {
        ChatSession session = ChatSession.builder()
                .sessionId(java.util.UUID.randomUUID())
                .customerId(1L)
                .department(ChatSession.Department.GENERAL)
                .status(ChatSession.Status.OPEN)
                .createdAt(LocalDateTime.now().minusHours(1))
                .build();

        ChatMessage message = ChatMessage.builder()
                .messageId(java.util.UUID.randomUUID())
                .sessionId(session.getSessionId())
                .senderId(1L)
                .senderRole(ChatMessage.SenderRole.CUSTOMER)
                .messageType(ChatMessage.MessageType.TEXT)
                .content("I have a question")
                .createdAt(LocalDateTime.now())
                .build();

        FraudDetectionEngine.FraudScore testScore = new FraudDetectionEngine.FraudScore(
                15.0,
                Collections.singletonMap("behavioral", 5.0),
                List.of()
        );

        FraudCase result = fraudEngine.createFraudCase(session, message, null, testScore);

        assertThat(result).isNull();
        verify(fraudCaseRepository, never()).save(any(FraudCase.class));
    }

    @Test
    @DisplayName("Should validate attachment with proper EXIF data")
    void shouldValidateAcceptableAttachment() {
        Attachment attachment = Attachment.builder()
                .attachmentId(java.util.UUID.randomUUID())
                .fileName("receipt.jpg")
                .fileSizeBytes(500000L)
                .mimeType("image/jpeg")
                .imageWidth(1200)
                .imageHeight(800)
                .exifDatetimeOriginal(LocalDateTime.now().minusDays(1))
                .exifMake("Canon")
                .exifModel("EOS R5")
                .analysisStatus(Attachment.AnalysisStatus.PENDING)
                .createdAt(LocalDateTime.now())
                .build();

        FraudDetectionEngine.AttachmentValidationResult mockResult = new FraudDetectionEngine.AttachmentValidationResult(
            false, null, true, List.of()
        );
        when(attachmentValidator.validateAttachment(attachment)).thenReturn(mockResult);

        FraudDetectionEngine.AttachmentValidationResult result = fraudEngine.validateAttachment(attachment);

        assertThat(result.isValid()).isTrue();
        assertThat(result.isDuplicate()).isFalse();
        assertThat(result.issues()).isEmpty();
    }
}
