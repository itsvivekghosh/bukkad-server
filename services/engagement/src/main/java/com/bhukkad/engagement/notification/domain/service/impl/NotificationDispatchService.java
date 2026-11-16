package com.bhukkad.engagement.notification.domain.service.impl;

import com.bhukkad.common.error.BusinessException;
import com.bhukkad.engagement.notification.domain.entity.Notification;
import com.bhukkad.engagement.notification.domain.repository.NotificationRepository;
import com.bhukkad.engagement.notification.domain.service.NotificationChannel;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import io.github.resilience4j.retry.annotation.Retry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * Dispatches outbound notifications through the configured
 * {@link NotificationChannel} strategy. The channel is simulated by default;
 * production swaps in FCM/SES/Twilio adapters. Idempotency per
 * recipient+channel is the caller's concern (common idempotency records).
 * 
 * <p>Optimizations:</p>
 * <ul>
 *   <li>Async dispatch with @Async for non-blocking notification sending</li>
 *   <li>Circuit breaker with fallback for provider failures</li>
 *   <li>Retry logic for transient failures</li>
 *   <li>Caffeine cache for channel support checks (10min TTL)</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
@EnableAsync
public class NotificationDispatchService {

    private final NotificationRepository repository;
    private final NotificationChannel channel;

    // Caffeine cache for channel support checks - 10min TTL, bounded to 100 entries
    private final Cache<String, Boolean> channelSupportCache = Caffeine.newBuilder()
        .maximumSize(100)
        .expireAfterWrite(Duration.ofMinutes(10))
        .build();

    /**
     * Async dispatch notification. Returns CompletableFuture for non-blocking
     * notification sending. Falls back gracefully if channel is unavailable.
     */
    @Async("notificationExecutor")
    @CircuitBreaker(name = "notificationChannel", fallbackMethod = "fallbackDispatch")
    @Retry(name = "notificationChannel")
    public CompletableFuture<Notification> dispatchAsync(String channelName, String recipient, 
                                                          String template, String subject, String body) {
        if (!supportsChannel(channelName)) {
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
        log.info("NOTIFICATION_SENT | channel={} | recipient={} | id={}", 
                channelName, recipient, notification.getId());
        return CompletableFuture.completedFuture(notification);
    }

    /**
     * Fallback dispatch when circuit breaker is open.
     * Queues notification for retry instead of failing.
     */
    public CompletableFuture<Notification> fallbackDispatch(String channelName, String recipient,
                                                            String template, String subject, String body,
                                                            Throwable ex) {
        log.error("NOTIFICATION_CIRCUIT_OPEN | channel={} | recipient={} | error={}",
                channelName, recipient, ex.getMessage());
        
        // Save as pending for later retry
        Notification notification = new Notification();
        notification.setChannel(channelName);
        notification.setRecipient(recipient);
        notification.setTemplate(template);
        notification.setSubject(subject);
        notification.setBody(body);
        notification.setStatus(Notification.STATUS_PENDING);
        notification = repository.save(notification);
        
        return CompletableFuture.completedFuture(notification);
    }

    /**
     * Synchronous dispatch (maintained for backward compatibility).
     * Wraps the async dispatch and blocks for result.
     */
    @Transactional
    public Notification dispatch(String channelName, String recipient, String template,
                                 String subject, String body) {
        try {
            return dispatchAsync(channelName, recipient, template, subject, body).get();
        } catch (Exception ex) {
            log.error("NOTIFICATION_DISPATCH_FAILED | channel={} | recipient={} | error={}",
                    channelName, recipient, ex.getMessage());
            throw new BusinessException("Notification dispatch failed: " + ex.getMessage());
        }
    }

    /**
     * Check if channel is supported with Caffeine caching.
     */
    private boolean supportsChannel(String channelName) {
        return channelSupportCache.get(channelName, key -> channel.supports(key));
    }

    @Transactional(readOnly = true)
    public List<Notification> history(String recipient, String channelName) {
        return repository.findByRecipientAndChannel(recipient, channelName);
    }
}
