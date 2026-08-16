package com.bhukkad.logging;

import org.springframework.core.task.TaskDecorator;

import java.util.Map;

/**
 * Propagates MDC (traceId, requestId, user context) into @Async worker threads.
 */
public class MdcTaskDecorator implements TaskDecorator {

    @Override
    public Runnable decorate(Runnable runnable) {
        Map<String, String> context = TraceContext.copy();
        return () -> {
            try {
                TraceContext.restore(context);
                runnable.run();
            } finally {
                TraceContext.clear();
            }
        };
    }
}
