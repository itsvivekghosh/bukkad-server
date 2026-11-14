package com.bhukkad.common.aws;

import org.springframework.stereotype.Service;
import software.amazon.awssdk.services.cloudformation.CloudFormationClient;
import software.amazon.awssdk.services.cloudformation.model.*;

import java.util.Map;

/**
 * CloudFormation service for Infrastructure as Code management.
 * <p>
 * Provides operations for creating, updating, and deleting CloudFormation
 * stacks to manage infrastructure declaratively. Fully testable against
 * Floci emulator locally.
 */
@Service
public class CloudFormationService {

    private final CloudFormationClient cloudFormationClient;
    private final AwsProperties awsProperties;

    public CloudFormationService(CloudFormationClient cloudFormationClient, AwsProperties awsProperties) {
        this.cloudFormationClient = cloudFormationClient;
        this.awsProperties = awsProperties;
    }

    /**
     * Creates a CloudFormation stack from a template.
     *
     * @param stackName    Stack name
     * @param templateBody CloudFormation template body (JSON/YAML)
     * @param parameters   Stack parameters
     * @return CreateStackResult
     */
    public CreateStackResponse createStack(String stackName, String templateBody, Map<String, String> parameters) {
        return cloudFormationClient.createStack(CreateStackRequest.builder()
                .stackName(stackName)
                .templateBody(templateBody)
                .parameters(parameters.entrySet().stream()
                        .map(e -> Parameter.builder()
                                .parameterKey(e.getKey())
                                .parameterValue(e.getValue())
                                .build())
                        .toList())
                .capabilities(Capability.CAPABILITY_NAMED_IAM)
                .build());
    }

    /**
     * Deletes a CloudFormation stack.
     */
    public DeleteStackResponse deleteStack(String stackName) {
        return cloudFormationClient.deleteStack(DeleteStackRequest.builder()
                .stackName(stackName)
                .build());
    }

    /**
     * Describes a stack's current status and outputs.
     */
    public DescribeStacksResponse describeStack(String stackName) {
        return cloudFormationClient.describeStacks(DescribeStacksRequest.builder()
                .stackName(stackName)
                .build());
    }

    /**
     * Lists all CloudFormation stacks.
     */
    public ListStacksResponse listStacks() {
        return cloudFormationClient.listStacks(ListStacksRequest.builder().build());
    }

    /**
     * Updates an existing stack with new template.
     */
    public UpdateStackResponse updateStack(String stackName, String templateBody, Map<String, String> parameters) {
        return cloudFormationClient.updateStack(UpdateStackRequest.builder()
                .stackName(stackName)
                .templateBody(templateBody)
                .parameters(parameters.entrySet().stream()
                        .map(e -> Parameter.builder()
                                .parameterKey(e.getKey())
                                .parameterValue(e.getValue())
                                .build())
                        .toList())
                .capabilities(Capability.CAPABILITY_NAMED_IAM)
                .build());
    }
}