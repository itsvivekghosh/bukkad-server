package com.bhukkad.admin.kafka.dlq;

import com.bhukkad.common.kafka.KafkaPlatformEventPublisher;
import com.bhukkad.common.kafka.KafkaPlatformProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.core.KafkaTemplate;

import java.time.Duration;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * DLQ consumer configuration - reads from dead letter topics and provides
 * management APIs for inspection and reprocessing.
 *
 * <p>Enabled when {@code app.events.external.dlq.enabled=true} (default false).
 * Uses a separate consumer group {@code <service>-dlq-consumer} to avoid
 * interfering with main event processing.</p>
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(KafkaPlatformProperties.class)
@ConditionalOnProperty(prefix = "app.events.external", name = "enabled", havingValue = "true", matchIfMissing = false)
@ConditionalOnProperty(prefix = "app.events.external", name = "type", havingValue = "kafka", matchIfMissing = false)
@ConditionalOnProperty(prefix = "app.events.external.dlq", name = "enabled", havingValue = "true", matchIfMissing = false)
@ConditionalOnClass(name = "org.springframework.kafka.core.KafkaTemplate")
@RequiredArgsConstructor
@Slf4j
public class DlqConsumerConfig {

    @Bean
    public DlqConsumerService dlqConsumerService(ObjectMapper objectMapper,
                                                  KafkaPlatformProperties properties,
                                                  org.apache.kafka.clients.consumer.KafkaConsumer<String, String> dlqKafkaConsumer) {
        return new DlqConsumerService(objectMapper, properties, dlqKafkaConsumer);
    }

    @Bean
    @ConditionalOnMissingBean(name = "dlqKafkaConsumer")
    public KafkaConsumer<String, String> dlqKafkaConsumer(ConsumerFactory<String, String> consumerFactory,
                                                          KafkaPlatformProperties properties) {
        var consumer = (KafkaConsumer<String, String>) consumerFactory.createConsumer("admin-dlq-consumer");
        
        // Subscribe to all DLQ topics for this service
        String baseTopic = properties.kafka().platformTopic();
        String dlqTopic = baseTopic + ".dlt";
        consumer.subscribe(Collections.singletonList(dlqTopic));
        
        log.info("DLQ consumer subscribed to: {}", dlqTopic);
        return consumer;
    }

    @Bean
    public DlqReprocessService dlqReprocessService(DlqConsumerService consumerService,
                                                    KafkaPlatformProperties properties,
                                                    com.bhukkad.common.kafka.KafkaPlatformEventPublisher kafkaPlatformEventPublisher,
                                                    ObjectMapper objectMapper) {
        return new DlqReprocessService(objectMapper, properties, kafkaPlatformEventPublisher, consumerService);
    }
}
