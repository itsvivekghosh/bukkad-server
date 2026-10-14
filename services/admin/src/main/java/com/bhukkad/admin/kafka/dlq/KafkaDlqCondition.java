package com.bhukkad.admin.kafka.dlq;

import org.springframework.context.annotation.Condition;
import org.springframework.context.annotation.ConditionContext;
import org.springframework.core.env.Environment;
import org.springframework.core.type.AnnotatedTypeMetadata;

/**
 * Condition to check if Kafka DLQ is enabled.
 */
public class KafkaDlqCondition implements org.springframework.context.annotation.Condition {

    @Override
    public boolean matches(ConditionContext context, AnnotatedTypeMetadata metadata) {
        Environment environment = context.getEnvironment();
        
        // Check if external events are enabled and type is kafka and dlq is enabled
        String enabled = environment.getProperty("app.events.external.enabled", "false");
        String type = environment.getProperty("app.events.external.type", "log");
        String dlqEnabled = environment.getProperty("app.events.external.dlq.enabled", "false");
        
        return "true".equalsIgnoreCase(enabled) 
                && "kafka".equalsIgnoreCase(type)
                && "true".equalsIgnoreCase(dlqEnabled);
    }
}