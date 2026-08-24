package com.bhukkad.logging;

import ch.qos.logback.classic.spi.ILoggingEvent;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ServiceLogDiscriminatorTest {

    private final ServiceLogDiscriminator discriminator = new ServiceLogDiscriminator();

    private String value(String loggerName) {
        return discriminator.getDiscriminatingValue(event(loggerName));
    }

    private static ILoggingEvent event(String name) {
        ILoggingEvent e = org.mockito.Mockito.mock(ILoggingEvent.class);
        org.mockito.Mockito.when(e.getLoggerName()).thenReturn(name);
        return e;
    }

    @Test void nullLogger_isCore() { assertEquals("core", value(null)); }
    @Test void emptyLogger_isCore() { assertEquals("core", value("")); }

    @Test void orderLogger_isOrder() { assertEquals("order", value("ORDER")); }
    @Test void orderServiceImpl_isOrder() { assertEquals("order", value("com.bhukkad.serviceImpl.OrderServiceImpl")); }
    @Test void orderController_isOrder() { assertEquals("order", value("com.bhukkad.controller.OrderController")); }
    @Test void orderPackage_isOrder() { assertEquals("order", value("com.bhukkad.order.OrderCreateJobService")); }
    @Test void orderScheduled_isOrder() { assertEquals("order", value("com.bhukkad.order.ScheduledOrderScheduler")); }
    @Test void orderEta_isOrder() { assertEquals("order", value("com.bhukkad.delivery.OrderEtaService")); }

    @Test void paymentLogger_isPayment() { assertEquals("payment", value("PAYMENT")); }
    @Test void paymentServiceImpl_isPayment() { assertEquals("payment", value("com.bhukkad.serviceImpl.PaymentServiceImpl")); }
    @Test void paymentPackage_isPayment() { assertEquals("payment", value("com.bhukkad.payment.PaymentGateway")); }
    @Test void paymentStrategy_isPayment() { assertEquals("payment", value("com.bhukkad.payment.strategy.RazorpayStrategy")); }

    @Test void notificationServiceImpl_isNotification() { assertEquals("notification", value("com.bhukkad.serviceImpl.NotificationServiceImpl")); }
    @Test void notificationDispatch_isNotification() { assertEquals("notification", value("com.bhukkad.notification.NotificationDispatchService")); }
    @Test void notificationPackage_isNotification() { assertEquals("notification", value("com.bhukkad.notification.push.FcmPushNotificationSender")); }

    @Test void securityLogger_isUser() { assertEquals("user", value("SECURITY")); }
    @Test void authServiceImpl_isUser() { assertEquals("user", value("com.bhukkad.serviceImpl.AuthServiceImpl")); }
    @Test void customerServiceImpl_isUser() { assertEquals("user", value("com.bhukkad.serviceImpl.CustomerServiceImpl")); }
    @Test void userRepository_isUser() { assertEquals("user", value("com.bhukkad.repository.UserRepository")); }
    @Test void userEntity_isUser() { assertEquals("user", value("com.bhukkad.entity.User")); }
    @Test void securityConfig_isUser() { assertEquals("user", value("com.bhukkad.config.SecurityConfig")); }
    @Test void jwtAuthFilter_isUser() { assertEquals("user", value("com.bhukkad.security.JwtAuthenticationFilter")); }
    @Test void userTierResolver_isUser() { assertEquals("user", value("com.bhukkad.ratelimit.UserTierResolver")); }

    @Test void springFramework_isCore() { assertEquals("core", value("org.springframework.web")); }
    @Test void hibernate_isCore() { assertEquals("core", value("org.hibernate.SQL")); }
    @Test void performanceLogger_isCore() { assertEquals("core", value("PERFORMANCE")); }
    @Test void alertLogger_isCore() { assertEquals("core", value("ALERT")); }
    @Test void cacheLogger_isCore() { assertEquals("core", value("com.bhukkad.cache.CacheWarmingService")); }
    @Test void loggingAspect_isCore() { assertEquals("core", value("com.bhukkad.logging.LoggingAspect")); }

    @Test void getKey_returnsService() { assertEquals("service", discriminator.getKey()); }
}