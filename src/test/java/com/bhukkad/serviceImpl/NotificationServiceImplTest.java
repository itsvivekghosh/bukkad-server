package com.bhukkad.serviceImpl;

import com.bhukkad.config.NotificationProperties;
import com.bhukkad.entity.Customer;
import com.bhukkad.entity.Order;
import com.bhukkad.entity.Restaurant;
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

import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
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

    private NotificationServiceImpl notificationService;

    @BeforeEach
    void setUp() {
        // Global switch on; all per-channel providers enabled so the real senders are invoked.
        notificationProperties.setEnabled(true);
        notificationProperties.getEmail().setEnabled(true);
        notificationProperties.getSms().setEnabled(true);
        notificationProperties.getWhatsapp().setEnabled(true);
        notificationProperties.getPush().setEnabled(true);
        notificationService = new NotificationServiceImpl(
                notificationProperties, orderRepository, userRepository, resilientEmailSender,
                smsSender, whatsAppSender, pushNotificationSender, notificationPreferenceService);
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

        User agent = new User();
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
}
