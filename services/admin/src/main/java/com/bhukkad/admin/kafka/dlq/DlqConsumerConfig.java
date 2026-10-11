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
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

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
@ConditionalOnExpression(
        "'${app.events.external.enabled:false}' == 'true' && '${app.events.external.type:log}' == 'kafka' && '${app.events.external.dlq.enabled:false}' == 'true'")
@ConditionalOnClass(name = "org.springframework.kafka.core.KafkaTemplate")
@RequiredArgsConstructor
@Slf4j
public class DlqConsumerConfig {

    private final KafkaPlatformProperties properties;
    private final Environment environment;

    @Bean
    public DlqConsumerService dlqConsumerService(ObjectMapper objectMapper,
                                                  KafkaPlatformProperties properties,
                                                  KafkaConsumer<String, String> dlqKafkaConsumer) {
        return new DlqConsumerService(objectMapper, properties, dlqKafkaConsumer);
    }

    @Bean
    @ConditionalOnMissingBean(name = "dlqKafkaConsumer")
    public KafkaConsumer<String, String> dlqKafkaConsumer(KafkaPlatformProperties properties) {
        var config = new java.util.HashMap<String, Object>();
        config.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, properties.kafka().bootstrapServers());
        config.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        config.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        config.put(ConsumerConfig.GROUP_ID_CONFIG, 
                environment.getProperty("spring.application.name", "app") + "-dlq-consumer");
        config.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false);
        config.put(ConsumerConfig.ISOLATION_LEVEL_CONFIG, "read_committed");
        config.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        config.put(ConsumerConfig.MAX_POLL_RECORDS_CONFIG, 100);
        
        var consumer = new KafkaConsumer<String, String>(config);
        
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
                                                    KafkaPlatformEventPublisher publisher,
                                                    ObjectMapper objectMapper) {
        return new DlqReprocessService(objectMapper, properties, publisher, consumerService);
    }
}