package com.bhukkad.common.kafka;

import com.bhukkad.common.event.PlatformEventMessage;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.config.YamlPropertiesFactoryBean;
import org.springframework.core.io.FileSystemResource;
import org.springframework.kafka.core.KafkaTemplate;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Properties;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

/**
 * Producer→consumer TOPIC-PARITY architecture test (audit-critical).
 *
 * <p>Pins the event-backbone routing contract end to end:</p>
 * <ol>
 *   <li>for every event type the catalog defines (docs/event-catalog.md), the
 *       real {@link KafkaPlatformConfig} publisher wiring writes the producing
 *       service's single BASE platform topic — never a per-type suffixed topic
 *       (the original defect: {@code order.events.v1.ordercreated} matched no
 *       listener and was seeded by no topic-job);</li>
 *   <li>every topic any {@code @KafkaListener} in the reactor consumes is a
 *       base platform topic that some service actually configures its
 *       publisher to write;</li>
 *   <li>for every live consumer, the event types it dispatches on are
 *       produced by a service whose base topic IS the listener's topic — the
 *       concrete producer→consumer pairs.</li>
 * </ol>
 *
 * <p>Producer topics are read from each service's real
 * {@code application.yml} ({@code app.events.external.kafka.platform-topic}),
 * so a config drift in any module fails this test. Consumer topics are
 * mirrored from the listener classes and re-verified against the actual
 * consumer source files on disk. Cross-module by nature, so it lives where
 * the publisher wiring lives; the mirror tables cite their source files.</p>
 *
 * <p>Known cross-batch routing gaps deliberately NOT asserted here (owned by
 * other batches): {@code PaymentRequestedConsumer} (payment) listens on
 * {@code payment.events.v1} while {@code payment_requested} is produced by
 * the order domain; {@code dispute_resolved} has no producer yet.</p>
 */
class KafkaTopicParityTest {

    /** Surefire runs with the module basedir (services/platform-lib) as working directory. */
    private static final Path REPO_ROOT = Path.of("../..").toAbsolutePath().normalize();

    /**
     * Producing service → its base platform topic, mirrored from each module's
     * application.yml and re-verified against that file in
     * {@link #producerTopicConfigMatchesTheCatalog()}.
     */
    private static final Map<String, String> PRODUCER_TOPICS = Map.of(
            "order", "order.events.v1",
            "payment", "payment.events.v1",
            "identity", "identity.events.v1",
            "restaurant", "restaurant.events.v1",
            "delivery", "delivery.events.v1",
            "notification", "notification.events.v1",
            "admin-analytics", "admin.events.v1",
            "survey", "bhukkad.platform.events");

    /** Catalog event type → producing service (docs/event-catalog.md "Event types"). */
    private static final Map<String, String> CATALOG_EVENTS = Map.ofEntries(
            Map.entry("CustomerRegistered", "identity"),
            Map.entry("AddressChanged", "identity"),
            Map.entry("RestaurantCreated", "restaurant"),
            Map.entry("RestaurantAvailabilityChanged", "restaurant"),
            Map.entry("MenuChanged", "restaurant"),
            Map.entry("OrderCreated", "order"),
            Map.entry("OrderStatusChanged", "order"),
            Map.entry("OrderPickedUp", "delivery"),
            Map.entry("OrderDelivered", "delivery"),
            Map.entry("DeliveryAssigned", "delivery"),
            Map.entry("PaymentSettled", "payment"),
            Map.entry("WalletCredited", "payment"),
            Map.entry("PaymentSettlementRunCompleted", "payment"),
            Map.entry("SurveySubmittedEvent", "survey"));

    /**
     * Live listener contract: consumer class → (listener topic → event types it
     * dispatches on, each mapped to its producing service). Saga-contract event
     * types produced outside the catalog table are included because real
     * consumers depend on them (OrderEventPublisher / PaymentService /
     * MenuEventsPublisher constants).
     */
    private static final Map<String, Map<String, Map<String, String>>> LISTENERS = buildListeners();

    private static Map<String, Map<String, Map<String, String>>> buildListeners() {
        Map<String, Map<String, Map<String, String>>> listeners = new LinkedHashMap<>();
        // services/notification/.../NotificationEventConsumer.java (TOPIC_ORDER_EVENTS)
        listeners.put("services/notification/src/main/java/com/bhukkad/notification/infrastructure/messaging/NotificationEventConsumer.java",
                Map.of("order.events.v1", Map.of("OrderCreated", "order")));
        // services/realtime/.../OrderLiveEventConsumer.java (TOPIC_ORDER_EVENTS)
        listeners.put("services/realtime/src/main/java/com/bhukkad/realtime/domain/service/impl/OrderLiveEventConsumer.java",
                Map.of("order.events.v1", Map.of("OrderCreated", "order", "OrderStatusChanged", "order")));
        // services/admin-analytics/.../AdminCqrsEventConsumer.java (TOPIC_ORDER_EVENTS)
        listeners.put("services/admin-analytics/src/main/java/com/bhukkad/admin/infrastructure/messaging/AdminCqrsEventConsumer.java",
                Map.of("order.events.v1", Map.of("OrderCreated", "order")));
        // services/survey/.../OrderItemsSnapshotConsumer.java (TOPIC_ORDER_EVENTS)
        listeners.put("services/survey/src/main/java/com/bhukkad/survey/infrastructure/messaging/OrderItemsSnapshotConsumer.java",
                Map.of("order.events.v1", Map.of("ORDER_ITEMS_SNAPSHOT", "order")));
        // services/order/.../PaymentSagaEventConsumer.java (TOPIC_PAYMENT_EVENTS + TOPIC_ORDER_EVENTS)
        listeners.put("services/order/src/main/java/com/bhukkad/order/domain/service/impl/PaymentSagaEventConsumer.java",
                Map.of("payment.events.v1", Map.of("payment_settled", "payment", "payment_failed", "payment"),
                        "order.events.v1", Map.of("stock_release_requested", "order")));
        // services/search/.../SearchSyncEventConsumer.java (TOPIC_RESTAURANT_EVENTS)
        listeners.put("services/search/src/main/java/com/bhukkad/search/infrastructure/messaging/SearchSyncEventConsumer.java",
                Map.of("restaurant.events.v1",
                        Map.of("restaurant_updated", "restaurant",
                                "menu_item_changed", "restaurant",
                                "menu_item_deleted", "restaurant")));
        // services/payment/.../consumer/* — listener topics verified in
        // everyListenerTopicIsProducedBySomeService; per-type pairing omitted
        // (payment_requested is order-produced; dispute_resolved has no
        // producer yet — cross-batch, see class javadoc).
        listeners.put("services/payment/src/main/java/com/bhukkad/payment/infrastructure/messaging/PaymentRequestedConsumer.java",
                Map.of("payment.events.v1", Map.of()));
        listeners.put("services/payment/src/main/java/com/bhukkad/payment/infrastructure/messaging/DisputeResolvedConsumer.java",
                Map.of("payment.events.v1", Map.of()));
        return listeners;
    }

    // ── 1. publisher side ─────────────────────────────────────────────────────

    /**
     * The core regression guard: through the REAL {@link KafkaPlatformConfig}
     * wiring, every catalog event type must be written to the producing
     * service's base platform topic exactly as configured — with NO per-type
     * suffix appended to the event type.
     */
    @Test
    @SuppressWarnings("unchecked")
    void everyCatalogEventType_isPublishedToTheProducerBaseTopic() {
        for (Map.Entry<String, String> event : CATALOG_EVENTS.entrySet()) {
            String service = event.getValue();
            String configuredTopic = configuredProducerTopic(service);

            KafkaTemplate<String, String> template = mock(KafkaTemplate.class);
            KafkaPlatformEventPublisher publisher = publisherFor(service, configuredTopic, template);

            publisher.publish(PlatformEventMessage.of(event.getKey(), "42", "{}"));

            ArgumentCaptor<String> topic = ArgumentCaptor.forClass(String.class);
            verify(template).send(topic.capture(),
                    ArgumentCaptor.forClass(String.class).capture(),
                    ArgumentCaptor.forClass(String.class).capture());
            assertThat(topic.getValue())
                    .as("event %s (producer %s) must be published to %s — the base topic its "
                            + "consumers subscribe to, with no per-type suffix",
                            event.getKey(), service, configuredTopic)
                    .isEqualTo(configuredTopic);
        }
    }

    /** The yml on disk must keep matching the catalog's producer topic table. */
    @Test
    void producerTopicConfigMatchesTheCatalog() {
        for (Map.Entry<String, String> expected : PRODUCER_TOPICS.entrySet()) {
            assertThat(configuredProducerTopic(expected.getKey()))
                    .as("services/%s application.yml platform-topic", expected.getKey())
                    .isEqualTo(expected.getValue());
        }
    }

    // ── 2. consumer side ──────────────────────────────────────────────────────

    /** Anything a listener consumes must be a topic some publisher writes. */
    @Test
    void everyListenerTopic_isProducedBySomeService() {
        for (Map.Entry<String, Map<String, Map<String, String>>> listener : LISTENERS.entrySet()) {
            for (String listenerTopic : listener.getValue().keySet()) {
                assertThat(PRODUCER_TOPICS.containsValue(listenerTopic))
                        .as("%s listens on %s, which no service's platform-topic produces",
                                listener.getKey(), listenerTopic)
                        .isTrue();
            }
        }
    }

    /**
     * The concrete producer→consumer pairs: for every event type a consumer
     * dispatches on, the producer's base topic equals the topic that consumer
     * listens on. This is the assertion that would have caught the original
     * outbox-relay vs. listener topic mismatch.
     */
    @Test
    void handledEventTypes_resolveToTheTopicTheListenerConsumes() {
        for (Map.Entry<String, Map<String, Map<String, String>>> listener : LISTENERS.entrySet()) {
            for (Map.Entry<String, Map<String, String>> subscription :
                    listener.getValue().entrySet()) {
                String listenerTopic = subscription.getKey();
                for (Map.Entry<String, String> handled : subscription.getValue().entrySet()) {
                    String eventType = handled.getKey();
                    String producer = handled.getValue();
                    assertThat(configuredProducerTopic(producer))
                            .as("%s handles %s (produced by %s) — producer topic must equal "
                                            + "the listener topic %s",
                                    listener.getKey(), eventType, producer, listenerTopic)
                            .isEqualTo(listenerTopic);
                }
            }
        }
    }

    /**
     * The mirrored listener topics must still match the real consumer sources.
     * Consumers either declare the topic as a constant ({@code "order.events.v1"})
     * or bind it via a property placeholder with the topic as its default
     * ({@code ${app.events.external.kafka.platform-topic:payment.events.v1}}) —
     * both contain the bare topic string.
     */
    @Test
    void listenerTopics_matchTheConsumerSourceFiles() throws Exception {
        for (Map.Entry<String, Map<String, Map<String, String>>> listener : LISTENERS.entrySet()) {
            Path source = REPO_ROOT.resolve(listener.getKey());
            assertThat(source).as("consumer source %s", listener.getKey()).exists();
            String code = Files.readString(source);
            for (String listenerTopic : listener.getValue().keySet()) {
                assertThat(code)
                        .as("%s must still reference listener topic %s", listener.getKey(), listenerTopic)
                        .contains(listenerTopic);
            }
        }
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    /** Builds the publisher exactly like KafkaPlatformConfig.kafkaPlatformEventPublisher does. */
    private KafkaPlatformEventPublisher publisherFor(String service, String topic,
                                                     KafkaTemplate<String, String> template) {
        var properties = new KafkaPlatformProperties(true, "kafka",
                new KafkaPlatformProperties.Kafka("localhost:9092", service + "-group",
                        topic, topic + ".dlt"), false);
        return new KafkaPlatformConfig().kafkaPlatformEventPublisher(template, properties);
    }

    /**
     * Reads {@code app.events.external.kafka.platform-topic} from the producing
     * service's application.yml, resolving {@code ${ENV:default}} placeholders
     * to their default (topics contain no ':' so first-colon split is safe).
     */
    private static String configuredProducerTopic(String service) {
        Path yml = REPO_ROOT.resolve("services").resolve(service)
                .resolve("src/main/resources/application.yml");
        assertThat(yml).as("application.yml of %s", service).exists();
        YamlPropertiesFactoryBean yaml = new YamlPropertiesFactoryBean();
        yaml.setResources(new FileSystemResource(yml.toFile()));
        Properties properties = yaml.getObject();
        String raw = properties.getProperty("app.events.external.kafka.platform-topic");
        assertThat(raw).as("platform-topic of %s", service).isNotBlank();
        String value = raw.trim();
        if (value.startsWith("${") && value.endsWith("}")) {
            String inner = value.substring(2, value.length() - 1);
            int separator = inner.indexOf(':');
            value = separator >= 0 ? inner.substring(separator + 1) : inner;
        }
        return value;
    }
}
