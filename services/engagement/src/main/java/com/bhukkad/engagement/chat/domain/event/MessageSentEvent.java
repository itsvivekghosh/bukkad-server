package com.bhukkad.engagement.chat.domain.event;

import com.bhukkad.engagement.chat.domain.entity.Attachment;
import com.bhukkad.engagement.chat.domain.entity.ChatMessage;
import com.bhukkad.engagement.chat.domain.entity.ChatSession;
import org.springframework.context.ApplicationEvent;

import java.util.List;
import java.util.UUID;

/**
 * Domain event published when a chat message is sent.
 * Enables async fraud detection processing via event-driven architecture.
 */
public class MessageSentEvent extends ApplicationEvent {

    private final UUID messageId;
    private final UUID sessionId;
    private final ChatSession session;
    private final ChatMessage message;
    private final List<Attachment> attachments;

    public MessageSentEvent(Object source, UUID messageId, UUID sessionId,
                            ChatSession session, ChatMessage message,
                            List<Attachment> attachments) {
        super(source);
        this.messageId = messageId;
        this.sessionId = sessionId;
        this.session = session;
        this.message = message;
        this.attachments = attachments;
    }

    public UUID getMessageId() { return messageId; }
    public UUID getSessionId() { return sessionId; }
    public ChatSession getSession() { return session; }
    public ChatMessage getMessage() { return message; }
    public List<Attachment> getAttachments() { return attachments; }
}
