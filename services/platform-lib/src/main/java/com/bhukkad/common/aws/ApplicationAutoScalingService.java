package com.bhukkad.common.aws;

import org.springframework.stereotype.Service;
import software.amazon.awssdk.services.applicationautoscaling.ApplicationAutoScalingClient;
import software.amazon.awssdk.services.applicationautoscaling.model.*;

/**
 * Application Auto Scaling service for auto-scaling policies.
 * <p>
 * Provides operations for managing scalable targets and scaling policies
 * for EC2, ECS, DynamoDB, and other scalable resources.
 * Fully testable against Floci emulator locally.
 */
@Service
public class ApplicationAutoScalingService {

    private final ApplicationAutoScalingClient applicationAutoScalingClient;
    private final AwsProperties awsProperties;

    public ApplicationAutoScalingService(ApplicationAutoScalingClient applicationAutoScalingClient, AwsProperties awsProperties) {
        this.applicationAutoScalingClient = applicationAutoScalingClient;
        this.awsProperties = awsProperties;
    }

    /**
     * Registers a scalable target for auto scaling.
     */
    public RegisterScalableTargetResponse registerScalableTarget(
            String serviceNamespace, String resourceId, String scalableDimension,
            int minCapacity, int maxCapacity) {
        return applicationAutoScalingClient.registerScalableTarget(RegisterScalableTargetRequest.builder()
                .serviceNamespace(serviceNamespace)
                .resourceId(resourceId)
                .scalableDimension(scalableDimension)
                .minCapacity(minCapacity)
                .maxCapacity(maxCapacity)
                .build());
    }

    /**
     * Describes scalable targets.
     */
    public DescribeScalableTargetsResponse describeScalableTargets(String serviceNamespace) {
        return applicationAutoScalingClient.describeScalableTargets(DescribeScalableTargetsRequest.builder()
                .serviceNamespace(serviceNamespace)
                .build());
    }

    /**
     * Describes scaling policies.
     */
    public DescribeScalingPoliciesResponse describeScalingPolicies(String serviceNamespace) {
        return applicationAutoScalingClient.describeScalingPolicies(DescribeScalingPoliciesRequest.builder()
                .serviceNamespace(serviceNamespace)
                .build());
    }

    /**
     * Deletes a scalable target.
     */
    public DeregisterScalableTargetResponse deregisterScalableTarget(
            String serviceNamespace, String resourceId, String scalableDimension) {
        return applicationAutoScalingClient.deregisterScalableTarget(DeregisterScalableTargetRequest.builder()
                .serviceNamespace(serviceNamespace)
                .resourceId(resourceId)
                .scalableDimension(scalableDimension)
                .build());
    }
}