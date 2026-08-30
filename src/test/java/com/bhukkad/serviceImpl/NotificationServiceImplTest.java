package com.bhukkad.serviceImpl;

import com.bhukkad.config.NotificationProperties;
import com.bhukkad.entity.Customer;
import com.bhukkad.entity.Order;
import com.bhukkad.entity.Restaurant;
import com.bhukkad.entity.DeliveryAgent;
import com.bhukkad.entity.User;
import com.bhukkad.exception.BusinessException;
import com.bhukkad.exception.ResourceNotFoundException;
import com.bhukkad.notification.ResilientEmailSender;
import com.bhukkad.notification.push.PushNotificationSender;
import com.bhukkad.notification.sms.SmsSender;
import com.bhukkad.notification.whatsapp.WhatsAppSender;
import com.bhukkad.repository.OrderRepository;
import com.bhukkad.repository.UserRepository;
import com.bhukkad.service.NotificationPreferenceService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link NotificationServiceImpl} — notification fan-out to
 * email/SMS/WhatsApp/push respecting per-channel preferences and the global
 * feature switch.
 */
@ExtendWith(MockitoExtension.class)
class NotificationServiceImplTest {

    private final NotificationProperties notificationProperties = new NotificationProperties();
    @Mock
    private OrderRepository orderRepository;
    @Mock
    private UserRepository userRepository;
    @Mock
    private ResilientEmailSender resilientEmailSender;
    @Mock
    private SmsSender smsSender;
    @Mock
    private WhatsAppSender whatsAppSender;
    @Mock
    private PushNotificationSender pushNotificationSender;
    @Mock
    private NotificationPreferenceService notificationPreferenceService;
    @Mock
    private ThreadPoolTaskExecutor lowPriorityTaskExecutor;

    private NotificationServiceImpl notificationService;

    @BeforeEach
    void setUp() {
        // Global switch on; all per-channel providers enabled so the real senders are invoked.
        notificationProperties.setEnabled(true);
        notificationProperties.getEmail().setEnabled(true);
        notificationProperties.getSms().setEnabled(true);
        notificationProperties.getWhatsapp().setEnabled(true);
        notificationProperties.getPush().setEnabled(true);
        // Batch D: channel fan-out runs on lowPriorityTaskExecutor. Run tasks
        // inline so the async dispatch is deterministic in unit tests (a bare
        // mock would drop the runnables and every future would only complete
        // via its orTimeout guard).
        org.mockito.Mockito.lenient().doAnswer(invocation -> {
            ((Runnable) invocation.getArgument(0)).run();
            return null;
        }).when(lowPriorityTaskExecutor).execute(any(Runnable.class));
        notificationService = new NotificationServiceImpl(
                notificationProperties, orderRepository, userRepository, resilientEmailSender,
                smsSender, whatsAppSender, pushNotificationSender, notificationPreferenceService, lowPriorityTaskExecutor);
    }

    private Order orderWithCustomer() {
        Customer customer = new Customer();
        customer.setId(7L);
        customer.setFullName("Aarav Sharma");
        customer.setEmail("aarav@bhukkad.test");
        customer.setPhoneNumber("9800000000");

        Restaurant restaurant = new Restaurant();
        restaurant.setId(10L);
        restaurant.setName("Pizza Palace");

        Order order = new Order();
        order.setId(42L);
        order.setOrderNumber("ORD-ABC12345");
        order.setCustomer(customer);
        order.setRestaurant(restaurant);
        order.setTotalAmount(500.0);
        order.setWalletAmountUsed(50.0);
        return order;
    }

    private void enableAllChannels() {
        when(notificationPreferenceService.isOrderUpdatesEnabled(7L)).thenReturn(true);
        when(notificationPreferenceService.isEmailEnabled(7L)).thenReturn(true);
        when(notificationPreferenceService.isSmsEnabled(7L)).thenReturn(true);
        when(notificationPreferenceService.isWhatsappEnabled(7L)).thenReturn(true);
        when(notificationPreferenceService.isPushEnabled(7L)).thenReturn(true);
    }

    @Test
    void sendOrderConfirmation_dispatchesAllEnabledChannels() {
        Order order = orderWithCustomer();
        when(orderRepository.findByIdWithDetails(42L)).thenReturn(Optional.of(order));
        enableAllChannels();
        // With all channels enabled and the global switch on, providers are invoked.

        notificationService.sendOrderConfirmation(42L);

        verify(resilientEmailSender).send(any());
        verify(smsSender).send(eq("9800000000"), anyString());
        verify(whatsAppSender).send(eq("9800000000"), anyString());
        verify(pushNotificationSender).sendToUser(eq(7L), anyString(), anyString());
    }

    @Test
    void sendOrderStatusUpdate_preferencesDisabled_skipsAllChannels() {
        Order order = orderWithCustomer();
        when(orderRepository.findByIdWithDetails(42L)).thenReturn(Optional.of(order));
        when(notificationPreferenceService.isOrderUpdatesEnabled(7L)).thenReturn(false);

        notificationService.sendOrderStatusUpdate(42L, "CONFIRMED");

        verify(resilientEmailSender, never()).send(any());
        verify(smsSender, never()).send(anyString(), anyString());
        verify(whatsAppSender, never()).send(anyString(), anyString());
        verify(pushNotificationSender, never()).sendToUser(anyLong(), anyString(), anyString());
    }

    @Test
    void sendDeliveryAssignment_notifiesCustomerAndAgent() {
        Order order = orderWithCustomer();
        when(orderRepository.findByIdWithDetails(42L)).thenReturn(Optional.of(order));
        enableAllChannels();

        DeliveryAgent agent = new DeliveryAgent();
        agent.setId(99L);
        agent.setEmail("agent@bhukkad.test");
        agent.setPhoneNumber("9900000000");
        when(userRepository.findById(99L)).thenReturn(Optional.of(agent));


        notificationService.sendDeliveryAssignment(42L, 99L);

        // Customer email + agent email = 2 sends
        verify(resilientEmailSender, times(2)).send(any());
        // Customer push
        verify(pushNotificationSender).sendToUser(eq(7L), anyString(), anyString());
        // Agent channels
        verify(smsSender).send(eq("9900000000"), anyString());
        verify(whatsAppSender).send(eq("9900000000"), anyString());
        verify(pushNotificationSender).sendToUser(eq(99L), anyString(), anyString());
    }

    @Test
    void sendTestNotification_emailChannel() {

        notificationService.sendTestNotification("email", "to@bhukkad.test", "hello");

        verify(resilientEmailSender).send(any());
    }

    @Test
    void sendTestNotification_unsupportedChannel_throws() {
        org.junit.jupiter.api.Assertions.assertThrows(BusinessException.class,
                () -> notificationService.sendTestNotification("pigeon", "x", "y"));
    }

    @Test
    void sendTestNotification_missingRecipient_throws() {
        org.junit.jupiter.api.Assertions.assertThrows(BusinessException.class,
                () -> notificationService.sendTestNotification("email", "", "y"));
    }

    @Test
    void sendOrderConfirmation_orderNotFound_throws() {
        when(orderRepository.findByIdWithDetails(999L)).thenReturn(Optional.empty());
        org.junit.jupiter.api.Assertions.assertThrows(ResourceNotFoundException.class,
                () -> notificationService.sendOrderConfirmation(999L));
    }

    // ==================== additional coverage ====================

    @Test
    void sendEmailVerification_sendsEmail() {
        notificationService.sendEmailVerification("user@bhukkad.test", "tok-123");

        verify(resilientEmailSender).send(any());
    }

    @Test
    void sendPasswordReset_sendsEmail() {
        notificationService.sendPasswordReset("user@bhukkad.test", "reset-456");

        verify(resilientEmailSender).send(any());
    }

    @Test
    void sendPaymentRefunded_dispatchesAllChannels() {
        Order order = orderWithCustomer();
        when(orderRepository.findByIdWithDetails(42L)).thenReturn(Optional.of(order));
        enableAllChannels();

        notificationService.sendPaymentRefunded(42L, 250.0);

        verify(resilientEmailSender).send(any());
        verify(smsSender).send(eq("9800000000"), anyString());
        verify(whatsAppSender).send(eq("9800000000"), anyString());
        verify(pushNotificationSender).sendToUser(eq(7L), anyString(), anyString());
    }

    @Test
    void sendTestNotification_smsChannel_sendsSms() {
        when(smsSender.send(eq("9800000000"), anyString())).thenReturn(true);

        notificationService.sendTestNotification("sms", "9800000000", "hello");

        verify(smsSender).send(eq("9800000000"), anyString());
    }

    @Test
    void sendTestNotification_whatsappChannel_sendsWhatsApp() {
        when(whatsAppSender.send(eq("9800000000"), anyString())).thenReturn(true);

        notificationService.sendTestNotification("whatsapp", "9800000000", "hello");

        verify(whatsAppSender).send(eq("9800000000"), anyString());
    }

    @Test
    void sendTestNotification_smsProviderFailure_throwsBusinessException() {
        when(smsSender.send(eq("9800000000"), anyString())).thenReturn(false);

        org.junit.jupiter.api.Assertions.assertThrows(BusinessException.class,
                () -> notificationService.sendTestNotification("sms", "9800000000", "hello"));
    }

    @Test
    void sendTestNotification_whatsappProviderFailure_throwsBusinessException() {
        when(whatsAppSender.send(eq("9800000000"), anyString())).thenReturn(false);

        org.junit.jupiter.api.Assertions.assertThrows(BusinessException.class,
                () -> notificationService.sendTestNotification("whatsapp", "9800000000", "hello"));
    }

    @Test
    void sendTestNotification_nullMessage_usesDefaultBody() {
        notificationService.sendTestNotification("email", "to@bhukkad.test", null);

        verify(resilientEmailSender).send(any());
    }

    @Test
    void sendOrderConfirmation_globalSwitchOff_logsInsteadOfSending() {
        // Batch D: the master switch short-circuits every provider without
        // dropping the fan-out — each helper logs and returns.
        notificationProperties.setEnabled(false);
        Order order = orderWithCustomer();
        when(orderRepository.findByIdWithDetails(42L)).thenReturn(Optional.of(order));
        enableAllChannels();

        notificationService.sendOrderConfirmation(42L);

        verify(resilientEmailSender, never()).send(any());
        verify(smsSender, never()).send(anyString(), anyString());
        verify(whatsAppSender, never()).send(anyString(), anyString());
        verify(pushNotificationSender, never()).sendToUser(anyLong(), anyString(), anyString());
    }

    @Test
    void sendOrderConfirmation_emailProviderThrows_sweepStillCompletes() {
        // A throwing provider must not break the fan-out: the exceptionally
        // guard logs and the remaining channels still dispatch.
        Order order = orderWithCustomer();
        when(orderRepository.findByIdWithDetails(42L)).thenReturn(Optional.of(order));
        enableAllChannels();
        doThrow(new RuntimeException("smtp down")).when(resilientEmailSender).send(any());

        org.junit.jupiter.api.Assertions.assertDoesNotThrow(() -> notificationService.sendOrderConfirmation(42L));

        verify(smsSender).send(eq("9800000000"), anyString());
        verify(whatsAppSender).send(eq("9800000000"), anyString());
        verify(pushNotificationSender).sendToUser(eq(7L), anyString(), anyString());
    }

    @Test
    void sendOrderConfirmation_smsWhatsappPushThrow_emailStillDispatches() {
        Order order = orderWithCustomer();
        when(orderRepository.findByIdWithDetails(42L)).thenReturn(Optional.of(order));
        enableAllChannels();
        doThrow(new RuntimeException("sms gateway down")).when(smsSender).send(anyString(), anyString());
        doThrow(new RuntimeException("wa gateway down")).when(whatsAppSender).send(anyString(), anyString());
        doThrow(new RuntimeException("fcm down")).when(pushNotificationSender).sendToUser(anyLong(), anyString(), anyString());

        org.junit.jupiter.api.Assertions.assertDoesNotThrow(() -> notificationService.sendOrderConfirmation(42L));

        verify(resilientEmailSender).send(any());
    }

    @Test
    void sendDeliveryAssignment_agentWithoutPhone_skipsAgentSmsAndWhatsapp() {
        Order order = orderWithCustomer();
        when(orderRepository.findByIdWithDetails(42L)).thenReturn(Optional.of(order));
        enableAllChannels();
        DeliveryAgent agent = new DeliveryAgent();
        agent.setId(99L);
        agent.setEmail("agent@bhukkad.test");
        agent.setPhoneNumber("   "); // blank → per-provider guard skips
        when(userRepository.findById(99L)).thenReturn(Optional.of(agent));

        notificationService.sendDeliveryAssignment(42L, 99L);

        // Only the customer SMS dispatches; the agent's blank number is skipped
        // by the per-provider guard.
        verify(smsSender, times(1)).send(anyString(), anyString());
        verify(smsSender).send(eq("9800000000"), anyString());
        verify(whatsAppSender, times(1)).send(anyString(), anyString());
        verify(whatsAppSender).send(eq("9800000000"), anyString());
        // Customer channels and both emails still go out.
        verify(resilientEmailSender, times(2)).send(any());
        verify(pushNotificationSender).sendToUser(eq(7L), anyString(), anyString());
        verify(pushNotificationSender).sendToUser(eq(99L), anyString(), anyString());
    }
}
