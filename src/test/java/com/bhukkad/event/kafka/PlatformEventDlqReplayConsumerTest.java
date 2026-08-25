package com.bhukkad.event.kafka;

import com.bhukkad.config.ExternalEventsProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;

import java.nio.charset.StandardCharsets;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PlatformEventDlqReplayConsumerTest {

    @Mock
    private KafkaTemplate<String, String> kafkaTemplate;

    private ExternalEventsProperties properties;
    private PlatformEventDlqReplayConsumer consumer;

    @BeforeEach
    void setUp() {
        properties = new ExternalEventsProperties();
        properties.getKafka().setPlatformTopic("bhukkad.platform.events");
        properties.getKafka().setDlqTopic("bhukkad.platform.events.dlt");
        consumer = new PlatformEventDlqReplayConsumer(kafkaTemplate, properties, new ObjectMapper());
    }

    private ConsumerRecord<String, String> record(String key, String payload) {
        return new ConsumerRecord<>("bhukkad.platform.events.dlt", 0, 0L, key, payload);
    }

    private ConsumerRecord<String, String> recordWithReplayCount(String key, String payload, String replayCount) {
        ConsumerRecord<String, String> r = record(key, payload);
        if (replayCount != null) {
            r.headers().add(PlatformEventDlqReplayConsumer.REPLAY_COUNT_HEADER,
                    replayCount.getBytes(StandardCharsets.UTF_8));
        }
        return r;
    }

    private void stubSend() {
        when(kafkaTemplate.send(any(ProducerRecord.class)))
                .thenReturn(CompletableFuture.completedFuture(null));
    }

    @Test
    void onDeadLetter_replaysPayloadToPlatformTopic() {
        stubSend();

        consumer.onDeadLetter(record("ORDER_CREATED", "payload"));

        ArgumentCaptor<ProducerRecord<String, String>> captor = ArgumentCaptor.forClass(ProducerRecord.class);
        verify(kafkaTemplate).send(captor.capture());
        assertEquals("bhukkad.platform.events", captor.getValue().topic());
        assertEquals("ORDER_CREATED", captor.getValue().key());
        assertEquals("payload", captor.getValue().value());
    }

    @Test
    void onDeadLetter_setsIncrementedReplayCountHeader() {
        stubSend();

        consumer.onDeadLetter(recordWithReplayCount("ORDER_CREATED", "payload", "0"));

        ArgumentCaptor<ProducerRecord<String, String>> captor = ArgumentCaptor.forClass(ProducerRecord.class);
        verify(kafkaTemplate).send(captor.capture());
        byte[] header = captor.getValue().headers()
                .lastHeader(PlatformEventDlqReplayConsumer.REPLAY_COUNT_HEADER).value();
        assertEquals("1", new String(header, StandardCharsets.UTF_8));
    }

    @Test
    void onDeadLetter_missingHeader_isFirstReplay() {
        stubSend();

        consumer.onDeadLetter(record("ORDER_CREATED", "payload"));

        ArgumentCaptor<ProducerRecord<String, String>> captor = ArgumentCaptor.forClass(ProducerRecord.class);
        verify(kafkaTemplate).send(captor.capture());
        byte[] header = captor.getValue().headers()
                .lastHeader(PlatformEventDlqReplayConsumer.REPLAY_COUNT_HEADER).value();
        assertEquals("1", new String(header, StandardCharsets.UTF_8));
    }

    @Test
    void onDeadLetter_replayCountAtMax_dropsWithoutReplay() {
        consumer.onDeadLetter(recordWithReplayCount("ORDER_CREATED", "poison", "3"));

        verify(kafkaTemplate, never()).send(any(ProducerRecord.class));
    }

    @Test
    void onDeadLetter_replayCountAboveMax_dropsWithoutReplay() {
        consumer.onDeadLetter(recordWithReplayCount("ORDER_CREATED", "poison", "7"));

        verify(kafkaTemplate, never()).send(any(ProducerRecord.class));
    }

    @Test
    void onDeadLetter_malformedReplayCount_treatedAsFirstReplay() {
        stubSend();

        consumer.onDeadLetter(recordWithReplayCount("K", "payload", "not-a-number"));

        ArgumentCaptor<ProducerRecord<String, String>> captor = ArgumentCaptor.forClass(ProducerRecord.class);
        verify(kafkaTemplate).send(captor.capture());
        assertEquals("K", captor.getValue().key());
        assertNotNull(captor.getValue().value());
    }

    @Test
    void onDeadLetter_sendFailure_loggedAndNotRethrown() {
        when(kafkaTemplate.send(any(ProducerRecord.class)))
                .thenThrow(new IllegalStateException("broker down"));

        org.junit.jupiter.api.Assertions.assertDoesNotThrow(
                () -> consumer.onDeadLetter(recordWithReplayCount("K", "payload", "0")));
    }
}