package com.bhukkad.config;

import com.bhukkad.payment.PaymentProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties({PaymentProperties.class, NotificationProperties.class})
public class PaymentNotificationConfig {
}
