package com.bhukkad.common.aws;

import org.springframework.stereotype.Service;
import software.amazon.awssdk.services.cloudwatchlogs.CloudWatchLogsClient;
import software.amazon.awssdk.services.cloudwatchlogs.model.*;

import java.util.List;

/**
 * AWS CloudWatch Logs service for centralized logging.
 * <p>
 * Provides operations for managing CloudWatch Logs.
 * <p>
 * Use cases in Bhukkad:
 * - Centralized logging for microservices
 *   <ul>
 *     <li>Fully testable against Floci emulator locally.</li>
 * </ul>
 */
@Service
public class CloudWatchLogsService {

    private final CloudWatchLogsClient cloudWatchLogsClient;

    public CloudWatchLogsService(CloudWatchLogsClient cloudWatchLogsClient) {
        this.cloudWatchLogsClient = cloudWatchLogsClient;
    }

    /**
     * Creates a log group.
     * <p>
     * Example usage:
     * <pre>
     *   CreateLogGroupResponse result = cloudWatchLogsService.createLogGroup("/bhukkad/identity");
     * </ul>
     */
    public CreateLogGroupResponse createLogGroup(String logGroupName) {
        return cloudWatchLogsClient.createLogGroup(CreateLogGroupRequest.builder()
                .logGroupName(logGroupName)
                .build());
    }

    /**
     * Describes log groups.
     */
    public DescribeLogGroupsResponse describeLogGroups() {
        return cloudWatchLogsClient.describeLogGroups(DescribeLogGroupsRequest.builder().build());
    }

    /**
     * Describes log streams in a log group.
     */
    public DescribeLogStreamsResponse describeLogStreams(String logGroupName) {
        return cloudWatchLogsClient.describeLogStreams(DescribeLogStreamsRequest.builder()
                .logGroupName(logGroupName)
                .build());
    }

    /**
     * Puts log events.
     * <p>
     * Example usage:
     * <pre>
     *   cloudWatchLogsService.putLogEvents("/bhukkad/identity", "my-stream", List.of(
 *           InputLogEvent.builder().message("Hello world").build()));
     * </ul>
     */
    public PutLogEventsResponse putLogEvents(
            String logGroupName, String logStreamName, List<InputLogEvent> logEvents) {
        return cloudWatchLogsClient.putLogEvents(PutLogEventsRequest.builder()
                .logGroupName(logGroupName)
                .logStreamName(logStreamName)
                .logEvents(logEvents)
                .build());
    }

    /**
     * Filters log events.
     */
    public FilterLogEventsResponse filterLogEvents(
            String logGroupName, String filterPattern) {
        return cloudWatchLogsClient.filterLogEvents(FilterLogEventsRequest.builder()
                .logGroupName(logGroupName)
                .filterPattern(filterPattern)
                .build());
    }
}