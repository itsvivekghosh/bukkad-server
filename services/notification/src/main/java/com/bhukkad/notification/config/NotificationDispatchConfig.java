package com.bhukkad.notification.config;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.ThreadPoolExecutor;

/**
 * Bounded dispatch pool for outbound notifications (PERF-2/P-07, audit guide
 * §5.3). Kafka consumer threads equal partition counts and inline SMTP/Twilio
 * HTTP takes 0.1–2 s per send, so one slow provider pins whole partitions and
 * risks {@code max.poll.interval} rebalance loops.
 *
 * <p>The hand-off is deliberately <strong>AbortPolicy</strong>, never
 * CallerRuns: a saturated pool (core 4 / max 16 / queue 2000) throws
 * {@link java.util.concurrent.RejectedExecutionException} back onto the
 * listener thread, which the platform Kafka {@code DefaultErrorHandler} routes
 * to {@code <topic>.dlt} — overflow becomes a visible, replayable dead-letter
 * backlog instead of silently degrading the consumer into synchronous
 * provider calls.</p>
 *
 * <p>Metric: {@code notification_dispatch_queue_depth} exposes the bounded
 * queue occupancy so alerting sees saturation before rejections start.</p>
 */
@Configuration(proxyBeanMethods = false)
public class NotificationDispatchConfig {

    public static final String DISPATCH_EXECUTOR = "notificationDispatchExecutor";

    @Bean(name = DISPATCH_EXECUTOR, destroyMethod = "shutdown")
    public ThreadPoolTaskExecutor notificationDispatchExecutor(MeterRegistry meterRegistry) {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(4);
        executor.setMaxPoolSize(16);
        executor.setQueueCapacity(2000);
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.AbortPolicy());
        executor.setThreadNamePrefix("notify-");
        executor.initialize();
        Gauge.builder("notification_dispatch_queue_depth", executor,
                        e -> e.getThreadPoolExecutor().getQueue().size())
                .description("Pending notification dispatch tasks in the bounded queue (P-07)")
                .register(meterRegistry);
        return executor;
    }
}
