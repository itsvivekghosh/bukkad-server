package com.bhukkad.common.logging;

import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.sift.AbstractDiscriminator;

import java.util.Locale;

/**
 * Routes each log event to a per-service log directory based on the logger
 * name. The Bhukkad monolith identifies its services through named loggers
 * ({@code ORDER}, {@code PAYMENT}, {@code SECURITY}) and package/class names;
 * this discriminator maps those to the directory names used by the
 * SiftingAppender template in logback-spring.xml:
 *
 * <pre>
 *   ORDER / *Order*            → order
 *   PAYMENT / *Payment*        → payment
 *   SECURITY / *Auth* / *User* / *Customer* / *Security* → user
 *   *Notification*             → notification
 *   everything else            → core
 * </pre>
 *
 * Because the value is derived from the event (not thread-local MDC), it is
 * safe with the async appender and background/scheduler threads.
 */
public class ServiceLogDiscriminator extends AbstractDiscriminator<ILoggingEvent> {

    public static final String KEY = "service";
    public static final String ORDER_SERVICE = "order";
    public static final String PAYMENT_SERVICE = "payment";

    @Override
    public String getDiscriminatingValue(ILoggingEvent event) {
        String loggerName = event.getLoggerName();
        if (loggerName == null) {
            return "core";
        }
        String name = loggerName.toLowerCase(Locale.ROOT);
        if (name.equals(ORDER_SERVICE) || name.contains(ORDER_SERVICE)) {
            return ORDER_SERVICE;
        }
        if (name.equals(PAYMENT_SERVICE) || name.contains(PAYMENT_SERVICE)) {
            return PAYMENT_SERVICE;
        }
        if (name.contains("notification")) {
            return "notification";
        }
        if (name.equals("security") || name.contains("security")
                || name.contains("auth") || name.contains("user") || name.contains("customer")) {
            return "user";
        }
        return "core";
    }

    @Override
    public String getKey() {
        return KEY;
    }
}
