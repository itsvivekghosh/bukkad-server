package com.bhukkad.serviceImpl;

import com.bhukkad.config.NotificationProperties;
import com.bhukkad.entity.Order;
import com.bhukkad.exception.ResourceNotFoundException;
import com.bhukkad.notification.push.PushNotificationSender;
import com.bhukkad.notification.sms.SmsSender;
import com.bhukkad.notification.ResilientEmailSender;
import com.bhukkad.repository.OrderRepository;
import com.bhukkad.repository.UserRepository;
import com.bhukkad.service.NotificationService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

@Slf4j
@Service
@RequiredArgsConstructor
public class NotificationServiceImpl implements NotificationService {

    private final NotificationProperties notificationProperties;
    private final OrderRepository orderRepository;
    private final UserRepository userRepository;
    private final ResilientEmailSender resilientEmailSender;
    private final SmsSender smsSender;
    private final PushNotificationSender pushNotificationSender;

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
            sendEmail(agent.getEmail(), "New delivery", agentBody);
            sendSms(agent.getPhoneNumber(), agentBody);
            sendPush(agent.getId(), "New delivery", agentBody);
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

    private void notifyCustomer(Order order, String subject, String body) {
        sendEmail(order.getCustomer().getEmail(), subject, body);
        sendSms(order.getCustomer().getPhoneNumber(), body);
        sendPush(order.getCustomer().getId(), subject, body);
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

    private void sendSms(String phoneNumber, String body) {
        if (!notificationProperties.isEnabled() || !notificationProperties.getSms().isEnabled()) {
            log.info("SMS | to={} | body={}", phoneNumber, body);
            return;
        }
        if (!StringUtils.hasText(phoneNumber)) {
            return;
        }
        smsSender.send(phoneNumber, body);
    }

    private void sendPush(Long userId, String title, String body) {
        if (!notificationProperties.isEnabled() || !notificationProperties.getPush().isEnabled()) {
            log.info("PUSH | userId={} | title={} | body={}", userId, title, body);
            return;
        }
        pushNotificationSender.sendToUser(userId, title, body);
    }
}
