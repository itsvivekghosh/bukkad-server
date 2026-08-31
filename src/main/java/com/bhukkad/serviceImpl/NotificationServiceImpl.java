package com.bhukkad.serviceImpl;

import com.bhukkad.config.NotificationProperties;
import com.bhukkad.entity.Order;
import com.bhukkad.exception.BusinessException;
import com.bhukkad.exception.ResourceNotFoundException;
import com.bhukkad.notification.push.PushNotificationSender;
import com.bhukkad.notification.sms.SmsSender;
import com.bhukkad.notification.ResilientEmailSender;
import com.bhukkad.notification.whatsapp.WhatsAppSender;
import com.bhukkad.repository.OrderRepository;
import com.bhukkad.repository.UserRepository;
import com.bhukkad.security.AccountFields;
import com.bhukkad.service.NotificationPreferenceService;
import com.bhukkad.service.NotificationService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;

@Slf4j
@Service
public class NotificationServiceImpl implements NotificationService {

    private final NotificationProperties notificationProperties;
    private final OrderRepository orderRepository;
    private final UserRepository userRepository;
    private final ResilientEmailSender resilientEmailSender;
    private final SmsSender smsSender;
    private final WhatsAppSender whatsAppSender;
    private final PushNotificationSender pushNotificationSender;
    private final NotificationPreferenceService notificationPreferenceService;
    private final ThreadPoolTaskExecutor lowPriorityTaskExecutor;

    public NotificationServiceImpl(NotificationProperties notificationProperties, OrderRepository orderRepository,
            UserRepository userRepository, ResilientEmailSender resilientEmailSender, SmsSender smsSender,
            WhatsAppSender whatsAppSender, PushNotificationSender pushNotificationSender,
            NotificationPreferenceService notificationPreferenceService, ThreadPoolTaskExecutor lowPriorityTaskExecutor) {
        this.notificationProperties = notificationProperties;
        this.orderRepository = orderRepository;
        this.userRepository = userRepository;
        this.resilientEmailSender = resilientEmailSender;
        this.smsSender = smsSender;
        this.whatsAppSender = whatsAppSender;
        this.pushNotificationSender = pushNotificationSender;
        this.notificationPreferenceService = notificationPreferenceService;
        this.lowPriorityTaskExecutor = lowPriorityTaskExecutor;
    }

    @Override
    public void sendOrderConfirmation(Long orderId) {
        Order order = findOrder(orderId);
        String subject = "Order confirmed: " + order.getOrderNumber();
        String body = "Hi " + order.getCustomer().getFullName()
                + ", your order from " + order.getRestaurant().getName()
                + " is confirmed. Total: ₹" + (order.getTotalAmount() + order.getWalletAmountUsed());
        notifyCustomer(order, subject, body);
    }

    @Override
    public void sendOrderStatusUpdate(Long orderId, String status) {
        Order order = findOrder(orderId);
        String body = "Order " + order.getOrderNumber() + " status updated to " + status;
        notifyCustomer(order, "Order update", body);
    }

    @Override
    public void sendDeliveryAssignment(Long orderId, Long agentId) {
        Order order = findOrder(orderId);
        String customerBody = "A rider has been assigned to order " + order.getOrderNumber();
        notifyCustomer(order, "Rider assigned", customerBody);

        userRepository.findById(agentId).ifPresent(agent -> {
            String agentBody = "New delivery assigned: " + order.getOrderNumber();
            List<CompletableFuture<Void>> agentFutures = new ArrayList<>();
            agentFutures.add(CompletableFuture.runAsync(() -> sendEmail(AccountFields.email(agent), "New delivery", agentBody), lowPriorityTaskExecutor)
                    .orTimeout(8, java.util.concurrent.TimeUnit.SECONDS)
                    .exceptionally(ex -> { log.warn("AGENT_EMAIL_TIMEOUT | agentId={}", agentId, ex); return null; }));
            agentFutures.add(CompletableFuture.runAsync(() -> sendSms(AccountFields.phoneNumber(agent), agentBody), lowPriorityTaskExecutor)
                    .orTimeout(5, java.util.concurrent.TimeUnit.SECONDS)
                    .exceptionally(ex -> { log.warn("AGENT_SMS_TIMEOUT | agentId={}", agentId, ex); return null; }));
            agentFutures.add(CompletableFuture.runAsync(() -> sendWhatsapp(AccountFields.phoneNumber(agent), agentBody), lowPriorityTaskExecutor)
                    .orTimeout(5, java.util.concurrent.TimeUnit.SECONDS)
                    .exceptionally(ex -> { log.warn("AGENT_WHATSAPP_TIMEOUT | agentId={}", agentId, ex); return null; }));
            agentFutures.add(CompletableFuture.runAsync(() -> sendPush(agent.getId(), "New delivery", agentBody), lowPriorityTaskExecutor)
                    .orTimeout(5, java.util.concurrent.TimeUnit.SECONDS)
                    .exceptionally(ex -> { log.warn("AGENT_PUSH_TIMEOUT | agentId={}", agentId, ex); return null; }));
            try {
                CompletableFuture.allOf(agentFutures.toArray(new CompletableFuture[0])).get();
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                log.warn("AGENT_NOTIFICATION_INTERRUPTED | agentId={}", agentId, ex);
            } catch (ExecutionException ex) {
                log.warn("AGENT_NOTIFICATION_PARTIAL_FAILURE | agentId={} | error={}", agentId, ex.getMessage());
            }
        });
    }

    @Override
    public void sendEmailVerification(String email, String token) {
        sendEmail(email, "Verify your Bhukkad email",
                "Use this token to verify your email: " + token);
    }

    @Override
    public void sendPasswordReset(String email, String token) {
        sendEmail(email, "Reset your Bhukkad password",
                "Use this token to reset your password: " + token);
    }

    @Override
    public void sendPaymentRefunded(Long orderId, Double amount) {
        Order order = findOrder(orderId);
        String body = "Your refund of ₹" + amount + " for order " + order.getOrderNumber()
                + " has been processed.";
        notifyCustomer(order, "Refund processed", body);
    }

    @Override
    public void sendTestNotification(String channel, String recipient, String message) {
        if (!StringUtils.hasText(channel) || !StringUtils.hasText(recipient)) {
            throw new BusinessException("channel and recipient are required");
        }
        String body = StringUtils.hasText(message) ? message : "Bhukkad test notification";
        switch (channel.toLowerCase()) {
            case "email" -> sendEmail(recipient, "Bhukkad Test", body);
            case "sms" -> {
                // OTP delivery: a real provider that failed must not silently
                // "succeed" — the caller (phone sign-in / registration) aborts
                // when delivery did not actually happen.
                if (!sendSms(recipient, body)) {
                    throw new BusinessException("Failed to send OTP via SMS. Please try again.");
                }
            }
            case "whatsapp" -> {
                if (!sendWhatsapp(recipient, body)) {
                    throw new BusinessException("Failed to send OTP via WhatsApp. Please try again.");
                }
            }
            default -> throw new BusinessException("Unsupported channel: " + channel);
        }
    }

    private void notifyCustomer(Order order, String subject, String body) {
        Long customerId = order.getCustomer().getId();
        if (!notificationPreferenceService.isOrderUpdatesEnabled(customerId)) {
            log.debug("Skipping order notification for customer {} (preferences disabled)", customerId);
            return;
        }
        List<CompletableFuture<Void>> futures = new ArrayList<>();
        if (notificationPreferenceService.isEmailEnabled(customerId)) {
            futures.add(CompletableFuture.runAsync(() -> sendEmail(order.getCustomer().getEmail(), subject, body), lowPriorityTaskExecutor)
                    .orTimeout(8, java.util.concurrent.TimeUnit.SECONDS)
                    .exceptionally(ex -> { log.warn("EMAIL_TIMEOUT | customerId={}", customerId, ex); return null; }));
        }
        if (notificationPreferenceService.isSmsEnabled(customerId)) {
            futures.add(CompletableFuture.runAsync(() -> sendSms(order.getCustomer().getPhoneNumber(), body), lowPriorityTaskExecutor)
                    .orTimeout(5, java.util.concurrent.TimeUnit.SECONDS)
                    .exceptionally(ex -> { log.warn("SMS_TIMEOUT | customerId={}", customerId, ex); return null; }));
        }
        if (notificationPreferenceService.isWhatsappEnabled(customerId)) {
            futures.add(CompletableFuture.runAsync(() -> sendWhatsapp(order.getCustomer().getPhoneNumber(), body), lowPriorityTaskExecutor)
                    .orTimeout(5, java.util.concurrent.TimeUnit.SECONDS)
                    .exceptionally(ex -> { log.warn("WHATSAPP_TIMEOUT | customerId={}", customerId, ex); return null; }));
        }
        if (notificationPreferenceService.isPushEnabled(customerId)) {
            futures.add(CompletableFuture.runAsync(() -> sendPush(customerId, subject, body), lowPriorityTaskExecutor)
                    .orTimeout(5, java.util.concurrent.TimeUnit.SECONDS)
                    .exceptionally(ex -> { log.warn("PUSH_TIMEOUT | customerId={}", customerId, ex); return null; }));
        }
        try {
            CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).get();
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            log.warn("NOTIFICATION_INTERRUPTED | customerId={}", customerId, ex);
        } catch (ExecutionException ex) {
            log.warn("NOTIFICATION_PARTIAL_FAILURE | customerId={} | error={}", customerId, ex.getMessage());
        }
    }

    private Order findOrder(Long orderId) {
        return orderRepository.findByIdWithDetails(orderId)
                .orElseThrow(() -> new ResourceNotFoundException("Order not found"));
    }

    private void sendEmail(String to, String subject, String body) {
        if (!notificationProperties.isEnabled() || !notificationProperties.getEmail().isEnabled()) {
            log.info("EMAIL | to={} | subject={} | body={}", to, subject, body);
            return;
        }
        SimpleMailMessage message = new SimpleMailMessage();
        message.setFrom(notificationProperties.getEmail().getFrom());
        message.setTo(to);
        message.setSubject(subject);
        message.setText(body);
        resilientEmailSender.send(message);
    }

    private boolean sendSms(String phoneNumber, String body) {
        if (!notificationProperties.isEnabled() || !notificationProperties.getSms().isEnabled()) {
            log.info("SMS | to={} | body={}", phoneNumber, body);
            return true;
        }
        if (!StringUtils.hasText(phoneNumber)) {
            return false;
        }
        return smsSender.send(phoneNumber, body);
    }

    private boolean sendWhatsapp(String phoneNumber, String body) {
        if (!notificationProperties.isEnabled() || !notificationProperties.getWhatsapp().isEnabled()) {
            log.info("WHATSAPP | to={} | body={}", phoneNumber, body);
            return true;
        }
        if (!StringUtils.hasText(phoneNumber)) {
            return false;
        }
        return whatsAppSender.send(phoneNumber, body);
    }

    private void sendPush(Long userId, String title, String body) {
        if (!notificationProperties.isEnabled() || !notificationProperties.getPush().isEnabled()) {
            log.info("PUSH | userId={} | title={} | body={}", userId, title, body);
            return;
        }
        pushNotificationSender.sendToUser(userId, title, body);
    }
}
