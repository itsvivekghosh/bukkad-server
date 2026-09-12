package com.bhukkad.notification.domain.service.impl;

import com.bhukkad.common.error.BusinessException;
import com.bhukkad.notification.domain.entity.Notification;
import com.bhukkad.notification.domain.repository.NotificationRepository;
import com.bhukkad.notification.domain.service.NotificationChannel;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * Dispatches outbound notifications through the configured
 * {@link NotificationChannel} strategy. The channel is simulated by default;
 * production swaps in FCM/SES/Twilio adapters. Idempotency per
 * recipient+channel is the caller's concern (common idempotency records).
 */
@Service
@RequiredArgsConstructor
public class NotificationDispatchService {

    private final NotificationRepository repository;
    private final NotificationChannel channel;

    @Transactional
    public Notification dispatch(String channelName, String recipient, String template,
                                 String subject, String body) {
        if (!channel.supports(channelName)) {
            throw new BusinessException("Unsupported channel: " + channelName);
        }
        Notification notification = new Notification();
        notification.setChannel(channelName);
        notification.setRecipient(recipient);
        notification.setTemplate(template);
        notification.setSubject(subject);
        notification.setBody(body);
        notification.setStatus(Notification.STATUS_PENDING);
        notification = repository.save(notification);

        channel.send(notification);

        notification.setStatus(Notification.STATUS_SENT);
        notification.setProviderRef("NOTIF-" + notification.getId());
        repository.save(notification);
        return notification;
    }

    @Transactional(readOnly = true)
    public List<Notification> history(String recipient, String channelName) {
        return repository.findByRecipientAndChannel(recipient, channelName);
    }
}