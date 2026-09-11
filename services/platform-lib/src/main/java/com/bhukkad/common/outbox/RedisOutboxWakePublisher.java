package com.bhukkad.common.outbox;

import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;

/**
 * Redis pub/sub {@link OutboxWakePublisher} (P-06): publishes the wake signal
 * to {@code bhukkad:outbox:wake:<service>} via {@link StringRedisTemplate}.
 *
 * <p>Failures are logged and swallowed — the wake is a latency optimisation
 * on top of the poll-only relay, so a Redis blip must never break (or even
 * mark failed) the business transaction that just enqueued the outbox row.</p>
 */
@Slf4j
public class RedisOutboxWakePublisher implements OutboxWakePublisher {

    private final StringRedisTemplate redisTemplate;
    private final String channel;

    public RedisOutboxWakePublisher(StringRedisTemplate redisTemplate, String serviceName) {
        this.redisTemplate = redisTemplate;
        this.channel = WAKE_CHANNEL_PREFIX + serviceName;
    }

    @Override
    public void publishAfterCommit(String eventType) {
        try {
            redisTemplate.convertAndSend(channel, eventType == null ? "" : eventType);
            log.debug("OUTBOX_WAKE_PUBLISHED | channel={} | type={}", channel, eventType);
        } catch (Exception ex) {
            // The periodic poll is the correctness backstop; never propagate.
            log.warn("OUTBOX_WAKE_PUBLISH_FAILED | channel={} | error={}", channel, ex.getMessage());
        }
    }
}
