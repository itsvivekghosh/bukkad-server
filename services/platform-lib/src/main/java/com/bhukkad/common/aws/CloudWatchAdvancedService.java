package com.bhukkad.common.aws;

import org.springframework.stereotype.Service;
import software.amazon.awssdk.services.cloudwatch.CloudWatchClient;
import software.amazon.awssdk.services.cloudwatch.model.*;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Advanced CloudWatch service for composite alarms, metric streams, and dashboards.
 * <p>
 * Note: Synthetics Canaries and RUM (Real User Monitoring) require separate SDK modules:
 * - software.amazon.awssdk:synthetics
 * - software.amazon.awssdk:cloudwatchrum
 * These are not included in the base CloudWatch client.
 */
@Service
public class CloudWatchAdvancedService {

    private final CloudWatchClient cloudWatchClient;
    private final AwsProperties awsProperties;

    public CloudWatchAdvancedService(CloudWatchClient cloudWatchClient, AwsProperties awsProperties) {
        this.cloudWatchClient = cloudWatchClient;
        this.awsProperties = awsProperties;
    }

    // ==================== Composite Alarms ====================

    /**
     * Creates a composite alarm that reduces alert noise by combining multiple alarms.
     * <p>
     * Example: Only alert if BOTH high error rate AND high latency occur simultaneously.
     */
    public void createCompositeAlarm(String alarmName, String alarmRule, String alarmDescription,
                                      List<String> okActions, List<String> alarmActions,
                                      List<String> insufficientDataActions) {
        cloudWatchClient.putCompositeAlarm(PutCompositeAlarmRequest.builder()
                .alarmName(alarmName)
                .alarmRule(alarmRule)
                .alarmDescription(alarmDescription)
                .actionsEnabled(true)
                .okActions(okActions)
                .alarmActions(alarmActions)
                .insufficientDataActions(insufficientDataActions)
                .build());
    }

    /**
     * Creates a composite alarm for API health (combines latency, error rate, throttle).
     */
    public void createApiHealthCompositeAlarm(String serviceName, String snsTopicArn) {
        String alarmName = "bhukkad-" + serviceName + "-api-health-composite";
        String alarmRule = String.format(
                "ALARM(\"%s-high-latency\") AND ALARM(\"%s-high-error-rate\")",
                serviceName, serviceName
        );

        createCompositeAlarm(alarmName, alarmRule,
                "Composite alarm for " + serviceName + " API health",
                List.of(snsTopicArn), List.of(snsTopicArn), List.of());
    }

    /**
     * Creates individual metric alarms that feed into composite alarms.
     */
    public void createLatencyAlarm(String serviceName, double threshold, String snsTopicArn) {
        String alarmName = "bhukkad-" + serviceName + "-high-latency";

        cloudWatchClient.putMetricAlarm(PutMetricAlarmRequest.builder()
                .alarmName(alarmName)
                .alarmDescription("High latency alarm for " + serviceName)
                .metricName("Latency")
                .namespace("AWS/ApiGateway")
                .extendedStatistic("p99")
                .period(60)
                .evaluationPeriods(3)
                .threshold(threshold)
                .comparisonOperator(ComparisonOperator.GREATER_THAN_THRESHOLD)
                .treatMissingData("missing")
                .alarmActions(snsTopicArn)
                .okActions(snsTopicArn)
                .dimensions(Dimension.builder().name("ServiceName").value(serviceName).build())
                .build());
    }

    public void createErrorRateAlarm(String serviceName, double threshold, String snsTopicArn) {
        String alarmName = "bhukkad-" + serviceName + "-high-error-rate";

        cloudWatchClient.putMetricAlarm(PutMetricAlarmRequest.builder()
                .alarmName(alarmName)
                .alarmDescription("High error rate alarm for " + serviceName)
                .metricName("5XXError")
                .namespace("AWS/ApiGateway")
                .statistic(Statistic.SUM)
                .period(60)
                .evaluationPeriods(2)
                .threshold(threshold)
                .comparisonOperator(ComparisonOperator.GREATER_THAN_THRESHOLD)
                .treatMissingData("missing")
                .alarmActions(snsTopicArn)
                .okActions(snsTopicArn)
                .dimensions(Dimension.builder().name("ServiceName").value(serviceName).build())
                .build());
    }

    public void createThrottleAlarm(String serviceName, double threshold, String snsTopicArn) {
        String alarmName = "bhukkad-" + serviceName + "-throttle";

        cloudWatchClient.putMetricAlarm(PutMetricAlarmRequest.builder()
                .alarmName(alarmName)
                .alarmDescription("Throttle alarm for " + serviceName)
                .metricName("Throttle")
                .namespace("AWS/ApiGateway")
                .statistic(Statistic.SUM)
                .period(60)
                .evaluationPeriods(1)
                .threshold(threshold)
                .comparisonOperator(ComparisonOperator.GREATER_THAN_THRESHOLD)
                .treatMissingData("missing")
                .alarmActions(snsTopicArn)
                .okActions(snsTopicArn)
                .dimensions(Dimension.builder().name("ServiceName").value(serviceName).build())
                .build());
    }

    // ==================== Metric Streams ====================

    /**
     * Creates a metric stream to send metrics to Kinesis Data Firehose
     * for long-term storage in S3 and querying with Athena.
     */
    public void createMetricStream(String streamName, String firehoseArn,
                                    List<String> includeNamespaces, List<String> excludeNamespaces) {
        cloudWatchClient.putMetricStream(PutMetricStreamRequest.builder()
                .name(streamName)
                .firehoseArn(firehoseArn)
                .outputFormat(MetricStreamOutputFormat.JSON)
                .includeFilters(includeNamespaces != null ? includeNamespaces.stream()
                        .map(ns -> MetricStreamFilter.builder().namespace(ns).build())
                        .toList() : List.of())
                .excludeFilters(excludeNamespaces != null ? excludeNamespaces.stream()
                        .map(ns -> MetricStreamFilter.builder().namespace(ns).build())
                        .toList() : List.of())
                .build());
    }

    /**
     * Creates the standard Bhukkad metric stream for all custom metrics.
     */
    public void createBhukkadMetricStream(String firehoseArn) {
        createMetricStream(
                "bhukkad-metrics-stream",
                firehoseArn,
                List.of("Bhukkad/*", "AWS/ApiGateway", "AWS/Lambda", "AWS/DynamoDB", "AWS/SQS", "AWS/SNS"),
                List.of("AWS/EC2") // Exclude EC2 metrics to reduce cost
        );
    }

    /**
     * Updates an existing metric stream.
     */
    public void updateMetricStream(String streamName, String firehoseArn) {
        cloudWatchClient.putMetricStream(PutMetricStreamRequest.builder()
                .name(streamName)
                .firehoseArn(firehoseArn)
                .outputFormat(MetricStreamOutputFormat.JSON)
                .build());
    }

    // ==================== Dashboard Creation ====================

    /**
     * Creates a standard Bhukkad dashboard with key metrics.
     */
    public void createBhukkadDashboard(String dashboardName) {
        String dashboardBody = """
            {
              "widgets": [
                {
                  "type": "metric",
                  "x": 0, "y": 0, "width": 12, "height": 6,
                  "properties": {
                    "metrics": [
                      ["Bhukkad/Orders", "OrdersCreated"],
                      ["Bhukkad/Orders", "OrdersCompleted"],
                      ["Bhukkad/Orders", "OrdersFailed"]
                    ],
                    "period": 300,
                    "stat": "Sum",
                    "region": "%s",
                    "title": "Order Volume"
                  }
                },
                {
                  "type": "metric",
                  "x": 12, "y": 0, "width": 12, "height": 6,
                  "properties": {
                    "metrics": [
                      ["Bhukkad/Orders", "OrderLatency", {"stat": "p50"}],
                      ["Bhukkad/Orders", "OrderLatency", {"stat": "p95"}],
                      ["Bhukkad/Orders", "OrderLatency", {"stat": "p99"}]
                    ],
                    "period": 300,
                    "stat": "p99",
                    "region": "%s",
                    "title": "Order Latency (p50/p95/p99)"
                  }
                },
                {
                  "type": "metric",
                  "x": 0, "y": 6, "width": 12, "height": 6,
                  "properties": {
                    "metrics": [
                      ["AWS/ApiGateway", "Latency", "ServiceName", "gateway"],
                      ["AWS/ApiGateway", "5XXError", "ServiceName", "gateway"],
                      ["AWS/ApiGateway", "Count", "ServiceName", "gateway"]
                    ],
                    "period": 60,
                    "stat": "Average",
                    "region": "%s",
                    "title": "API Gateway Health"
                  }
                },
                {
                  "type": "metric",
                  "x": 12, "y": 6, "width": 12, "height": 6,
                  "properties": {
                    "metrics": [
                      ["AWS/DynamoDB", "ConsumedReadCapacityUnits", "TableName", "bhukkad-orders"],
                      ["AWS/DynamoDB", "ConsumedWriteCapacityUnits", "TableName", "bhukkad-orders"],
                      ["AWS/DynamoDB", "ThrottledRequests", "TableName", "bhukkad-orders"]
                    ],
                    "period": 300,
                    "stat": "Sum",
                    "region": "%s",
                    "title": "DynamoDB Orders Table"
                  }
                },
                {
                  "type": "metric",
                  "x": 0, "y": 12, "width": 12, "height": 6,
                  "properties": {
                    "metrics": [
                      ["AWS/SQS", "NumberOfMessagesSent", "QueueName", "bhukkad-order-events.fifo"],
                      ["AWS/SQS", "NumberOfMessagesReceived", "QueueName", "bhukkad-order-events.fifo"],
                      ["AWS/SQS", "ApproximateAgeOfOldestMessage", "QueueName", "bhukkad-order-events.fifo"]
                    ],
                    "period": 300,
                    "stat": "Sum",
                    "region": "%s",
                    "title": "Order Queue Health"
                  }
                },
                {
                  "type": "metric",
                  "x": 12, "y": 12, "width": 12, "height": 6,
                  "properties": {
                    "metrics": [
                      ["AWS/Lambda", "Duration", "FunctionName", "bhukkad-process-payment"],
                      ["AWS/Lambda", "Errors", "FunctionName", "bhukkad-process-payment"],
                      ["AWS/Lambda", "Invocations", "FunctionName", "bhukkad-process-payment"]
                    ],
                    "period": 300,
                    "stat": "Average",
                    "region": "%s",
                    "title": "Payment Lambda"
                  }
                }
              ]
            }
            """.formatted(
                awsProperties.getRegion(), awsProperties.getRegion(),
                awsProperties.getRegion(), awsProperties.getRegion(),
                awsProperties.getRegion(), awsProperties.getRegion()
        );

        cloudWatchClient.putDashboard(PutDashboardRequest.builder()
                .dashboardName(dashboardName)
                .dashboardBody(dashboardBody)
                .build());
    }
}