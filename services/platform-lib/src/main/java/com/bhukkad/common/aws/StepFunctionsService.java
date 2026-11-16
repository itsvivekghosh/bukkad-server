package com.bhukkad.common.aws;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import software.amazon.awssdk.services.sfn.SfnClient;
import software.amazon.awssdk.services.sfn.model.*;

import java.util.Map;
import java.util.UUID;

/**
 * Step Functions service for managing state machine executions.
 * <p>
 * Provides high-level operations for starting, monitoring, and managing
 * Step Functions workflows (order saga, loyalty recalculation, etc.).
 */
@Service
public class StepFunctionsService {

    private final SfnClient sfnClient;
    private final AwsProperties awsProperties;
    private final StepFunctionsStateMachines stateMachines;

    public StepFunctionsService(SfnClient sfnClient, AwsProperties awsProperties, StepFunctionsStateMachines stateMachines) {
        this.sfnClient = sfnClient;
        this.awsProperties = awsProperties;
        this.stateMachines = stateMachines;
    }

    /**
     * Creates all state machines in AWS Step Functions.
     * Should be called during application startup or deployment.
     */
    public void createStateMachines() {
        String roleArn = "arn:aws:iam::" + awsProperties.getAccountId() + ":role/bhukkad-stepfunctions-role";

        stateMachines.getAllDefinitions().forEach((name, definition) -> {
            String stateMachineName = "bhukkad-" + name.toLowerCase();

            try {
                CreateStateMachineRequest request = CreateStateMachineRequest.builder()
                        .name(stateMachineName)
                        .definition(definition)
                        .roleArn(roleArn)
                        .type(StateMachineType.STANDARD)
                        .loggingConfiguration(LoggingConfiguration.builder()
                                .level(LogLevel.ALL)
                                .includeExecutionData(true)
                                .destinations(LogDestination.builder()
                                        .cloudWatchLogsLogGroup(CloudWatchLogsLogGroup.builder()
                                                .logGroupArn("arn:aws:logs:" + awsProperties.getRegion() + ":" + awsProperties.getAccountId() + ":log-group:/aws/stepfunctions/bhukkad-" + name.toLowerCase() + ":*")
                                                .build())
                                        .build())
                                .build())
                        .tracingConfiguration(TracingConfiguration.builder()
                                .enabled(true)
                                .build())
                        .build();

                sfnClient.createStateMachine(request);
            } catch (StateMachineAlreadyExistsException e) {
                // Update existing state machine
                UpdateStateMachineRequest updateRequest = UpdateStateMachineRequest.builder()
                        .stateMachineArn("arn:aws:states:" + awsProperties.getRegion() + ":" + awsProperties.getAccountId() + ":stateMachine:" + stateMachineName)
                        .definition(definition)
                        .loggingConfiguration(LoggingConfiguration.builder()
                                .level(LogLevel.ALL)
                                .includeExecutionData(true)
                                .build())
                        .build();
                sfnClient.updateStateMachine(updateRequest);
            }
        });
    }

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /**
     * Starts an order saga execution.
     */
    public String startOrderSaga(Map<String, Object> input) {
        String executionName = "order-" + UUID.randomUUID().toString().substring(0, 8);
        String stateMachineArn = "arn:aws:states:" + awsProperties.getRegion() + ":" + awsProperties.getAccountId() + ":stateMachine:bhukkad-ordersaga";

        StartExecutionRequest request = StartExecutionRequest.builder()
                .stateMachineArn(stateMachineArn)
                .name(executionName)
                .input(toJson(input))
                .traceHeader("root=" + UUID.randomUUID().toString() + ";parent=" + UUID.randomUUID().toString() + ";sampled=1")
                .build();

        return sfnClient.startExecution(request).executionArn();
    }

    /**
     * Starts a simple order execution.
     */
    public String startSimpleOrder(Map<String, Object> input) {
        String executionName = "simple-order-" + UUID.randomUUID().toString().substring(0, 8);
        String stateMachineArn = "arn:aws:states:" + awsProperties.getRegion() + ":" + awsProperties.getAccountId() + ":stateMachine:bhukkad-simpleorder";

        StartExecutionRequest request = StartExecutionRequest.builder()
                .stateMachineArn(stateMachineArn)
                .name(executionName)
                .input(toJson(input))
                .build();

        return sfnClient.startExecution(request).executionArn();
    }

    private String toJson(Map<String, Object> input) {
        try {
            return MAPPER.writeValueAsString(input);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to serialize input", e);
        }
    }

    /**
     * Gets the current status of an execution.
     */
    public ExecutionStatus getExecutionStatus(String executionArn) {
        DescribeExecutionRequest request = DescribeExecutionRequest.builder()
                .executionArn(executionArn)
                .build();

        DescribeExecutionResponse response = sfnClient.describeExecution(request);
        return ExecutionStatus.fromString(response.statusAsString());
    }

    /**
     * Stops a running execution.
     */
    public void stopExecution(String executionArn, String cause) {
        sfnClient.stopExecution(StopExecutionRequest.builder()
                .executionArn(executionArn)
                .cause(cause)
                .error("StoppedByUser")
                .build());
    }

    /**
     * Gets execution history for debugging.
     */
    public GetExecutionHistoryResponse getExecutionHistory(String executionArn) {
        return sfnClient.getExecutionHistory(GetExecutionHistoryRequest.builder()
                .executionArn(executionArn)
                .maxResults(100)
                .build());
    }

    /**
     * Redrives a failed execution (retries from failure point).
     */
    public void redriveExecution(String executionArn) {
        sfnClient.redriveExecution(RedriveExecutionRequest.builder()
                .executionArn(executionArn)
                .build());
    }

    public enum ExecutionStatus {
        RUNNING, SUCCEEDED, FAILED, TIMED_OUT, ABORTED;

        public static ExecutionStatus fromString(String status) {
            return switch (status) {
                case "RUNNING" -> RUNNING;
                case "SUCCEEDED" -> SUCCEEDED;
                case "FAILED" -> FAILED;
                case "TIMED_OUT" -> TIMED_OUT;
                case "ABORTED" -> ABORTED;
                default -> throw new IllegalArgumentException("Unknown status: " + status);
            };
        }
    }
}