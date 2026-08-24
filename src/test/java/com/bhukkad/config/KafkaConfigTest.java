package com.bhukkad.config;

import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.core.*;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class KafkaConfigTest {

    @Mock
    private ExternalEventsProperties properties;

    @Mock
    private ExternalEventsProperties.Kafka kafka;

    private final KafkaConfig config = new KafkaConfig();

    @Test
    void producerFactory_createsDefaultKafkaProducerFactory() {
        when(properties.getKafka()).thenReturn(kafka);
        when(kafka.getBootstrapServers()).thenReturn("localhost:9092");

        ProducerFactory<String, String> factory = config.producerFactory(properties);

        assertNotNull(factory);
        assertTrue(factory instanceof DefaultKafkaProducerFactory);
    }

    @Test
    void kafkaTemplate_createsWithProducerFactory() {
        DefaultKafkaProducerFactory<String, String> pf = new DefaultKafkaProducerFactory<>(
                java.util.Map.of(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, "localhost:9092"));

        KafkaTemplate<String, String> template = config.kafkaTemplate(pf);

        assertNotNull(template);
    }

    @Test
    void consumerFactory_createsDefaultKafkaConsumerFactory() {
        when(properties.getKafka()).thenReturn(kafka);
        when(kafka.getBootstrapServers()).thenReturn("localhost:9092");
        when(kafka.getConsumerGroup()).thenReturn("test-group");

        ConsumerFactory<String, String> factory = config.consumerFactory(properties);

        assertNotNull(factory);
        assertTrue(factory instanceof DefaultKafkaConsumerFactory);
    }

    @Test
    void kafkaListenerContainerFactory_createsWithConsumerFactory() {
        DefaultKafkaConsumerFactory<String, String> cf = new DefaultKafkaConsumerFactory<>(
                java.util.Map.of(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, "localhost:9092"));

        ConcurrentKafkaListenerContainerFactory<String, String> factory =
                config.kafkaListenerContainerFactory(cf);

        assertNotNull(factory);
        assertNotNull(factory.getConsumerFactory());
    }
}