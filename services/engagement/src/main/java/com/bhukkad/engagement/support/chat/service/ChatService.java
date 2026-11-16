package com.bhukkad.engagement.support.chat.service;

import com.bhukkad.engagement.support.chat.api.dto.request.CreateChatRoomRequest;
import com.bhukkad.engagement.support.chat.api.dto.request.SendMessageRequest;
import com.bhukkad.engagement.support.chat.api.dto.response.ChatMessageResponse;
import com.bhukkad.engagement.support.chat.api.dto.response.ChatRoomResponse;
import com.bhukkad.engagement.support.chat.api.dto.response.AttachmentResponse;
import com.bhukkad.engagement.support.chat.api.dto.response.FraudAlertResponse;
import com.bhukkad.engagement.support.chat.domain.entity.Attachment;
import com.bhukkad.engagement.support.chat.domain.entity.ChatMessage;
import com.bhukkad.engagement.support.chat.domain.entity.ChatRoom;
import com.bhukkad.engagement.support.chat.domain.entity.FraudAlert;
import com.bhukkad.engagement.support.chat.domain.entity.FraudAlert.Severity;
import com.bhukkad.engagement.support.chat.domain.entity.OrderReference;
import com.bhukkad.engagement.support.chat.domain.repository.AttachmentRepository;
import com.bhukkad.engagement.support.chat.domain.repository.ChatMessageRepository;
import com.bhukkad.engagement.support.chat.domain.repository.ChatRoomRepository;
import com.bhukkad.engagement.support.chat.domain.repository.FraudAlertRepository;
import com.bhukkad.engagement.support.chat.domain.repository.OrderReferenceRepository;
import com.bhukkad.engagement.support.chat.fraud.FraudDetectionEngine;
import com.bhukkad.engagement.support.chat.fraud.FraudDetectionEngine.FraudAnalysisResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * ChatService - Business logic for chat support operations.
 *
 * <p>Handles room creation, message sending, file attachments, and fraud
 * detection integration. All methods are transactional to ensure data
 * consistency.</p>
 */
@Service
public class ChatService {

    private static final Logger log = LoggerFactory.getLogger(ChatService.class);

    private final ChatRoomRepository roomRepository;
    private final ChatMessageRepository messageRepository;
    private final AttachmentRepository attachmentRepository;
    private final FraudAlertRepository fraudAlertRepository;
    private final OrderReferenceRepository orderReferenceRepository;
    private final FraudDetectionEngine fraudDetectionEngine;
    private final AttachmentService attachmentService;

    public ChatService(
            ChatRoomRepository roomRepository,
            ChatMessageRepository messageRepository,
            AttachmentRepository attachmentRepository,
            FraudAlertRepository fraudAlertRepository,
            OrderReferenceRepository orderReferenceRepository,
            FraudDetectionEngine fraudDetectionEngine,
            AttachmentService attachmentService) {
        this.roomRepository = roomRepository;
        this.messageRepository = messageRepository;
        this.attachmentRepository = attachmentRepository;
        this.fraudAlertRepository = fraudAlertRepository;
        this.orderReferenceRepository = orderReferenceRepository;
        this.fraudDetectionEngine = fraudDetectionEngine;
        this.attachmentService = attachmentService;
    }

    /**
     * Create a new chat room for customer support.
     */
    @Transactional
    public ChatRoomResponse createRoom(CreateChatRoomRequest request) {
        ChatRoom room = ChatRoom.builder()
            .customerId(request.customerId())
            .customerName(request.customerName())
            .customerEmail(request.customerEmail())
            .orderId(request.orderId())
            .deliveryId(request.deliveryId())
            .restaurantId(request.restaurantId())
            .status(ChatRoom.ChatRoomStatus.OPEN)
            .subject(request.subject())
            .build();

        room = roomRepository.save(room);

        // Link order if provided
        if (request.orderId() != null) {
            OrderReference orderRef = OrderReference.builder()
                .roomId(room.getId())
                .orderId(request.orderId())
                .customerId(request.customerId())
                .build();
            orderReferenceRepository.save(orderRef);
        }

        // Send initial message if provided
        if (request.initialMessage() != null && !request.initialMessage().isBlank()) {
            SendMessageRequest msgRequest = new SendMessageRequest(
                room.getId(),
                request.customerId(),
                request.customerName(),
                "CUSTOMER",
                "TEXT",
                request.initialMessage(),
                null
            );
            sendMessage(msgRequest);
        }

        return mapToResponse(room);
    }

    /**
     * Send a message in a chat room with fraud detection.
     */
    @Transactional
    public ChatMessageResponse sendMessage(SendMessageRequest request) {
        ChatRoom room = roomRepository.findById(request.roomId())
            .orElseThrow(() -> new IllegalArgumentException("Room not found: " + request.roomId()));

        ChatMessage message = ChatMessage.builder()
            .roomId(request.roomId())
            .senderId(request.senderId())
            .senderName(request.senderName())
            .senderRole(request.senderRole())
            .messageType(request.messageType())
            .content(request.content())
            .attachmentId(request.attachmentId())
            .build();

        // Run fraud detection
        FraudAnalysisResult fraudResult = fraudDetectionEngine.analyzeMessage(message);
        message.setFraudScore(fraudResult.fraudScore());
        message.setFraudReasons(fraudResult.fraudReasons());
        message.setIsAutoFlagged(fraudResult.isAutoFlagged());

        message = messageRepository.save(message);

        // Create fraud alert if flagged
        if (fraudResult.isAutoFlagged()) {
            createFraudAlert(room.getId(), message.getId(), request.senderId(),
                fraudResult.fraudScore(), fraudResult.severity(),
                fraudResult.fraudReasons());
        }

        // Update room stats
        room.setTotalMessages(room.getTotalMessages() + 1);
        room.setLastMessageAt(LocalDateTime.now());
        if (fraudResult.fraudScore() > room.getFraudScore()) {
            room.setFraudScore(fraudResult.fraudScore());
            room.setFraudFlagged(fraudResult.isAutoFlagged());
            room.setFraudReasons(fraudResult.fraudReasons());
        }
        roomRepository.save(room);

        return mapToResponse(message);
    }

    /**
     * Upload an attachment for a message.
     */
    @Transactional
    public AttachmentResponse uploadAttachment(Long roomId, Long messageId, Long uploadedBy,
                                               MultipartFile file) {
        Attachment attachment = attachmentService.storeAttachment(roomId, messageId, uploadedBy, file);

        // Run fraud detection on attachment
        FraudAnalysisResult fraudResult = fraudDetectionEngine.analyzeAttachment(
            attachment, roomId, uploadedBy);

        if (fraudResult.isAutoFlagged()) {
            createFraudAlert(roomId, messageId, uploadedBy,
                fraudResult.fraudScore(), fraudResult.severity(),
                fraudResult.fraudReasons());
        }

        return mapToResponse(attachment);
    }

    /**
     * Get chat room by public ID.
     */
    @Transactional(readOnly = true)
    public ChatRoomResponse getRoom(UUID publicId) {
        ChatRoom room = roomRepository.findByPublicId(publicId)
            .orElseThrow(() -> new IllegalArgumentException("Room not found: " + publicId));
        return mapToResponse(room);
    }

    /**
     * Get messages for a room with pagination.
     * 
     * <p>Optimization: Batch loads all attachments for messages in a single
     * query instead of N+1 individual queries per message.</p>
     */
    @Transactional(readOnly = true)
    public Page<ChatMessageResponse> getMessages(Long roomId, int page, int size) {
        Pageable pageable = PageRequest.of(page, size, Sort.by(Sort.Direction.ASC, "createdAt"));
        Page<ChatMessage> messages = messageRepository.findByRoomId(roomId, pageable);
        
        // Batch load all attachments for messages to avoid N+1 queries
        Map<Long, Attachment> attachmentMap = loadAttachmentsForMessages(messages.getContent());
        Map<Long, AttachmentResponse> attachmentResponseMap = attachmentMap.entrySet().stream()
            .collect(Collectors.toMap(
                Map.Entry::getKey,
                entry -> mapToResponse(entry.getValue())
            ));
        
        return messages.map(message -> mapToResponse(message, attachmentResponseMap));
    }

    /**
     * Get all rooms for a customer.
     */
    @Transactional(readOnly = true)
    public List<ChatRoomResponse> getRoomsByCustomer(Long customerId) {
        return roomRepository.findByCustomerId(customerId).stream()
            .map(this::mapToResponse)
            .collect(Collectors.toList());
    }

    /**
     * Get fraud alerts (admin only).
     */
    @Transactional(readOnly = true)
    public List<FraudAlertResponse> getFraudAlerts(Boolean reviewed) {
        if (reviewed != null) {
            return fraudAlertRepository.findByReviewed(reviewed).stream()
                .map(this::mapToResponse)
                .collect(Collectors.toList());
        }
        return fraudAlertRepository.findAll().stream()
            .map(this::mapToResponse)
            .collect(Collectors.toList());
    }

    /**
     * Mark fraud alert as reviewed.
     */
    @Transactional
    public FraudAlertResponse reviewFraudAlert(UUID publicId, String reviewedBy, String reviewNote, String actionTaken) {
        FraudAlert alert = fraudAlertRepository.findByPublicId(publicId)
            .orElseThrow(() -> new IllegalArgumentException("Alert not found: " + publicId));
        alert.setReviewed(true);
        alert.setReviewedBy(reviewedBy);
        alert.setReviewedAt(LocalDateTime.now());
        alert.setReviewNote(reviewNote);
        alert.setActionTaken(actionTaken);
        alert = fraudAlertRepository.save(alert);
        return mapToResponse(alert);
    }

    /**
     * Get fraud statistics for dashboard.
     */
    public FraudDetectionEngine.FraudStats getFraudStats(LocalDateTime since) {
        return fraudDetectionEngine.getFraudStats(since);
    }

        // ==================== Mapping Methods ====================

    private ChatRoomResponse mapToResponse(ChatRoom room) {
        return new ChatRoomResponse(
            room.getPublicId(),
            room.getCustomerId(),
            room.getCustomerName(),
            room.getCustomerEmail(),
            room.getAgentId(),
            room.getAgentName(),
            room.getOrderId(),
            room.getDeliveryId(),
            room.getRestaurantId(),
            room.getStatus(),
            room.getSubject(),
            room.getFraudScore(),
            room.getFraudFlagged(),
            room.getFraudReasons(),
            room.getTotalMessages(),
            room.getLastMessageAt(),
            room.getCreatedAt(),
            room.getResolvedAt()
        );
    }

    private ChatMessageResponse mapToResponse(ChatMessage message) {
        return mapToResponse(message, Map.of());
    }

    private ChatMessageResponse mapToResponse(ChatMessage message, Map<Long, AttachmentResponse> attachmentResponseMap) {
        AttachmentResponse attachmentResponse = message.getAttachmentId() != null
            ? attachmentResponseMap.get(message.getAttachmentId())
            : null;
        return new ChatMessageResponse(
            message.getPublicId(),
            message.getRoomId(),
            message.getSenderId(),
            message.getSenderName(),
            message.getSenderRole(),
            message.getMessageType(),
            message.getContent(),
            message.getAttachmentId(),
            attachmentResponse,
            message.getFraudScore(),
            message.getFraudReasons(),
            message.getIsAutoFlagged(),
            message.getIsRead(),
            message.getCreatedAt()
        );
    }

    /**
     * Batch loads attachments for a list of messages to avoid N+1 queries.
     * 
     * <p>Optimization: Instead of querying the database once per message
     * attachment, this method collects all attachment IDs and loads them
     * in a single query.</p>
     */
    private Map<Long, Attachment> loadAttachmentsForMessages(List<ChatMessage> messages) {
        List<Long> attachmentIds = messages.stream()
            .map(ChatMessage::getAttachmentId)
            .filter(id -> id != null)
            .distinct()
            .toList();
        
        if (attachmentIds.isEmpty()) {
            return Map.of();
        }
        
        List<Attachment> attachments = attachmentRepository.findAllById(attachmentIds);
        return attachments.stream()
            .collect(Collectors.toMap(Attachment::getId, a -> a, (a1, a2) -> a1));
    }

    private FraudAlertResponse mapToResponse(FraudAlert alert) {
        return new FraudAlertResponse(
            alert.getPublicId(),
            alert.getRoomId(),
            alert.getMessageId(),
            alert.getCustomerId(),
            alert.getAlertType(),
            alert.getSeverity(),
            alert.getRiskScore(),
            alert.getDescription(),
            alert.getEvidence(),
            alert.getReviewed(),
            alert.getReviewedBy(),
            alert.getReviewedAt(),
            alert.getReviewNote(),
            alert.getActionTaken(),
            alert.getCreatedAt()
        );
    }

    private AttachmentResponse mapToResponse(Attachment attachment) {
        return new AttachmentResponse(
            attachment.getPublicId(),
            attachment.getMessageId(),
            attachment.getRoomId(),
            attachment.getUploadedBy(),
            attachment.getOriginalFilename(),
            attachment.getStoredFilename(),
            attachment.getMimeType(),
            attachment.getFileSizeBytes(),
            attachment.getStorageUrl(),
            attachment.getStorageProvider(),
            attachment.getFileHash(),
            attachment.getExifMetadata(),
            attachment.getExifAvailable(),
            attachment.getIsTampered(),
            attachment.getUploadedAt()
        );
    }

    private void createFraudAlert(Long roomId, Long messageId, Long customerId,
                                   int riskScore, Severity severity, String description) {
        FraudAlert alert = FraudAlert.builder()
            .roomId(roomId)
            .messageId(messageId)
            .customerId(customerId)
            .alertType(determineAlertType(description))
            .severity(severity)
            .riskScore(riskScore)
            .description(description)
            .evidence(description)
            .build();
        fraudAlertRepository.save(alert);
        log.warn("Fraud alert created: roomId={}, messageId={}, severity={}, score={}",
            roomId, messageId, severity, riskScore);
    }

    private String determineAlertType(String fraudReasons) {
        if (fraudReasons == null || fraudReasons.isBlank()) {
            return "GENERAL";
        }
        String lower = fraudReasons.toLowerCase();
        if (lower.contains("duplicate")) {
            return "DUPLICATE_MESSAGE";
        } else if (lower.contains("refund scam")) {
            return "REFUND_KEYWORD";
        } else if (lower.contains("tamper") || lower.contains("exif")) {
            return "IMAGE_TAMPERING";
        } else if (lower.contains("velocity")) {
            return "VELOCITY";
        } else if (lower.contains("order mismatch")) {
            return "ORDER_MISMATCH";
        } else {
            return "GENERAL";
        }
    }
}
