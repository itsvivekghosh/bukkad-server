package com.bhukkad.common.aws;

import org.springframework.stereotype.Service;
import software.amazon.awssdk.services.ec2.Ec2Client;
import software.amazon.awssdk.services.ec2.model.*;

/**
 * EC2 service for compute instance management.
 * <p>
 * Provides operations for managing EC2 instances, security groups, and
 * key pairs. Fully testable against Floci emulator locally.
 */
@Service
public class Ec2Service {

    private final Ec2Client ec2Client;
    private final AwsProperties awsProperties;

    public Ec2Service(Ec2Client ec2Client, AwsProperties awsProperties) {
        this.ec2Client = ec2Client;
        this.awsProperties = awsProperties;
    }

    /**
     * Launches EC2 instances.
     */
    public RunInstancesResponse runInstances(
            String imageId, String instanceType, String subnetId, int minCount, int maxCount) {
        return ec2Client.runInstances(RunInstancesRequest.builder()
                .imageId(imageId)
                .instanceType(InstanceType.fromValue(instanceType))
                .subnetId(subnetId)
                .minCount(minCount)
                .maxCount(maxCount)
                .build());
    }

    /**
     * Describes EC2 instances.
     */
    public DescribeInstancesResponse describeInstances() {
        return ec2Client.describeInstances(DescribeInstancesRequest.builder().build());
    }

    /**
     * Stops EC2 instances.
     */
    public StopInstancesResponse stopInstances(java.util.List<String> instanceIds) {
        return ec2Client.stopInstances(StopInstancesRequest.builder()
                .instanceIds(instanceIds)
                .build());
    }

    /**
     * Starts EC2 instances.
     */
    public StartInstancesResponse startInstances(java.util.List<String> instanceIds) {
        return ec2Client.startInstances(StartInstancesRequest.builder()
                .instanceIds(instanceIds)
                .build());
    }

    /**
     * Terminates EC2 instances.
     */
    public TerminateInstancesResponse terminateInstances(java.util.List<String> instanceIds) {
        return ec2Client.terminateInstances(TerminateInstancesRequest.builder()
                .instanceIds(instanceIds)
                .build());
    }

    /**
     * Creates a security group.
     */
    public CreateSecurityGroupResponse createSecurityGroup(
            String groupName, String description, String vpcId) {
        return ec2Client.createSecurityGroup(CreateSecurityGroupRequest.builder()
                .groupName(groupName)
                .description(description)
                .vpcId(vpcId)
                .build());
    }
}