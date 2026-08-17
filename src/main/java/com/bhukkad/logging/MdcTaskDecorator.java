package com.bhukkad.logging;

import org.springframework.core.task.TaskDecorator;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.Map;

/**
 * Propagates MDC (traceId, requestId, user context) and Spring Security context
 * into @Async worker threads.
 */
public class MdcTaskDecorator implements TaskDecorator {

    @Override
    public Runnable decorate(Runnable runnable) {
        Map<String, String> context = TraceContext.copy();
        SecurityContext securityContext = SecurityContextHolder.getContext();
        return () -> {
            try {
                TraceContext.restore(context);
                SecurityContextHolder.setContext(securityContext);
                runnable.run();
            } finally {
                SecurityContextHolder.clearContext();
                TraceContext.clear();
            }
        };
    }
}
