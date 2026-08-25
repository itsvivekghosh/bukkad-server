package com.bhukkad.event.kafka;

import com.bhukkad.config.ExternalEventsProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.KafkaHeaders;
import org.springframework.stereotype.Component;

/**
 * Replays messages that landed on the platform-events DLQ topic back onto the
 * main topic so the regular consumer group can process them again. This closes
 * the gap where the DLQ was previously write-only (manual/planned replay).
 *
 * <p>Poison-pill guard: a message that has been replayed more than
 * {@value #MAX_REPLAYS} times is dropped with a CRITICAL log so a permanently
 * unprocessable message does not loop forever. Replay attempts travel in a
 * {@code replayCount} header on the re-sent message.</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnExpression("'${app.events.external.enabled:false}' == 'true' && '${app.events.external.type:log}' == 'kafka'")
public class PlatformEventDlqReplayConsumer {

    static final String REPLAY_COUNT_HEADER = "replayCount";
    static final int MAX_REPLAYS = 3;

    private final KafkaTemplate<String, String> kafkaTemplate;
    private final ExternalEventsProperties externalEventsProperties;
    private final ObjectMapper objectMapper;

    @KafkaListener(
            topics = "${app.events.external.kafka.dlq-topic}",
            groupId = "${app.events.external.kafka.consumer-group}-dlq-replay")
    public void onDeadLetter(ConsumerRecord<String, String> record) {
        String payload = record.value();
        int replayCount = parseReplayCount(headerValue(record, REPLAY_COUNT_HEADER));

        if (replayCount >= MAX_REPLAYS) {
            log.error("KAFKA_DLQ_DROPPED | topic={} | replayCount={} | payload={}",
                    externalEventsProperties.getKafka().getDlqTopic(), replayCount, truncate(payload, 300));
            return;
        }

        try {
            String platformTopic = externalEventsProperties.getKafka().getPlatformTopic();
            org.apache.kafka.clients.producer.ProducerRecord<String, String> producerRecord =
                    new org.apache.kafka.clients.producer.ProducerRecord<>(platformTopic, record.key(), payload);
            producerRecord.headers().add(REPLAY_COUNT_HEADER,
                    String.valueOf(replayCount + 1).getBytes(java.nio.charset.StandardCharsets.UTF_8));
            kafkaTemplate.send(producerRecord);
            log.warn("KAFKA_DLQ_REPLAYED | from={} | to={} | replayCount={}",
                    externalEventsProperties.getKafka().getDlqTopic(), platformTopic, replayCount + 1);
        } catch (Exception ex) {
            log.error("KAFKA_DLQ_REPLAY_FAILED | replayCount={} | error={}",
                    replayCount, ex.getMessage(), ex);
        }
    }

    private String headerValue(ConsumerRecord<String, String> record, String name) {
        var header = record.headers().lastHeader(name);
        return header == null ? null : new String(header.value(), java.nio.charset.StandardCharsets.UTF_8);
    }

    private int parseReplayCount(String header) {
        if (header == null) {
            return 0;
        }
        try {
            return Integer.parseInt(header);
        } catch (NumberFormatException ex) {
            return 0;
        }
    }

    private static String truncate(String s, int max) {
        return s == null ? "null" : (s.length() <= max ? s : s.substring(0, max) + "...");
    }
}
