package com.bhukkad.common.aws;

import org.springframework.stereotype.Service;
import software.amazon.awssdk.services.eventbridge.EventBridgeClient;
import software.amazon.awssdk.services.eventbridge.model.*;

import java.util.List;
import java.util.Map;

/**
 * EventBridge service for domain-specific event buses.
 * <p>
 * Provides operations for creating and managing EventBridge event buses
 * for different business domains. Each domain (orders, payments, customers,
 * catalog) has its own event bus for better isolation, routing, and
 * independent scaling.
 * <p>
 * Fully testable against Floci emulator locally.
 */
@Service
public class EventBridgeService {

    private final EventBridgeClient eventBridgeClient;
    private final AwsProperties awsProperties;

    public EventBridgeService(EventBridgeClient eventBridgeClient, AwsProperties awsProperties) {
        this.eventBridgeClient = eventBridgeClient;
        this.awsProperties = awsProperties;
    }

    /**
     * Creates domain-specific event buses for Bhukkad microservices.
     * <p>
     * Creates separate event buses for:
     * - orders: Order lifecycle events (created, confirmed, preparing, delivered)
     * - payments: Payment events (processed, failed, refunded)
     * - customers: Customer events (registered, updated, deleted)
     * - catalog: Catalog events (item created, updated, deleted)
     * - engagement: Social events (post created, comment added, like)
     *
     * Should be called during application startup or deployment.
     */
    public void createDomainEventBuses() {
        List<String> domains = List.of("orders", "payments", "customers", "catalog", "engagement");

        for (String domain : domains) {
            String eventBusName = "bhukkad-" + domain + "-events";
            try {
                eventBridgeClient.createEventBus(CreateEventBusRequest.builder()
                        .name(eventBusName)
                        .description("Bhukkad " + domain + " domain event bus")
                        .build());
            } catch (ResourceAlreadyExistsException e) {
                // Event bus already exists, skip
            }
        }
    }

    /**
     * Publishes an event to a domain-specific event bus.
     *
     * @param eventBusName Event bus name (e.g., "bhukkad-orders-events")
     * @param source       Event source (e.g., "bhukkad.commerce")
     * @param detailType   Event detail type (e.g., "OrderCreated")
     * @param detail       Event detail as JSON string
     * @return PutEventsResult
     */
    public PutEventsResponse publishEvent(String eventBusName, String source,
                                           String detailType, String detail) {
        PutEventsRequestEntry entry = PutEventsRequestEntry.builder()
                .eventBusName(eventBusName)
                .source(source)
                .detailType(detailType)
                .detail(detail)
                .build();

        return eventBridgeClient.putEvents(PutEventsRequest.builder()
                .entries(entry)
                .build());
    }

    /**
     * Publishes multiple events to a domain-specific event bus in a batch.
     *
     * @param eventBusName Event bus name
     * @param entries      List of event entries
     * @return PutEventsResult
     */
    public PutEventsResponse publishEvents(String eventBusName, List<EventEntry> entries) {
        List<PutEventsRequestEntry> requestEntries = entries.stream()
                .map(e -> PutEventsRequestEntry.builder()
                        .eventBusName(eventBusName)
                        .source(e.source())
                        .detailType(e.detailType())
                        .detail(e.detail())
                        .build())
                .toList();

        return eventBridgeClient.putEvents(PutEventsRequest.builder()
                .entries(requestEntries)
                .build());
    }

    /**
     * Creates an EventBridge rule that routes events from one bus to another
     * or to a target (SQS, Lambda, SNS).
     *
     * @param ruleName       Rule name
     *   @param eventBusName   Event bus name
     *   @param eventPattern   Event pattern (JSON) for filtering
     *   @param targetArn      Target ARN (SQS, Lambda, SNS, etc.)
     *   @param targetId       Target ID
     */
    public void createEventRule(String ruleName, String eventBusName,
                                 String eventPattern, String targetArn, String targetId) {
        // Create the rule
        eventBridgeClient.putRule(PutRuleRequest.builder()
                .name(ruleName)
                .eventBusName(eventBusName)
                .eventPattern(eventPattern)
                .state(RuleState.ENABLED)
                .description("Bhukkad event routing rule: " + ruleName)
                .build());

        // Add target to the rule
        eventBridgeClient.putTargets(PutTargetsRequest.builder()
                .rule(ruleName)
                .eventBusName(eventBusName)
                .targets(Target.builder()
                        .id(targetId)
                        .arn(targetArn)
                        .build())
                .build());
    }

    /**
     * Lists all event buses.
     */
    public List<EventBus> listEventBuses() {
        return eventBridgeClient.listEventBuses(ListEventBusesRequest.builder().build()).eventBuses();
    }

    /**
     * Describes an event bus configuration.
     */
    public EventBus describeEventBus(String eventBusName) {
        DescribeEventBusResponse response = eventBridgeClient.describeEventBus(DescribeEventBusRequest.builder()
                .name(eventBusName)
                .build());
        return EventBus.builder()
                .name(response.name())
                .arn(response.arn())
                .policy(response.policy())
                .build();
    }

    /**
     * Entry for publishing events.
     */
    public record EventEntry(String source, String detailType, String detail) {}
}