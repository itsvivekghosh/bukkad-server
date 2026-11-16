package com.bhukkad.common.aws;

import org.springframework.stereotype.Service;
import software.amazon.awssdk.services.ecs.EcsClient;
import software.amazon.awssdk.services.ecs.model.*;

/**
 * ECS service for container orchestration management.
 * <p>
 * Provides operations for managing ECS clusters, services, and task definitions.
 * Fully testable against Floci emulator locally.
 */
@Service
public class EcsService {

    private final EcsClient ecsClient;
    private final AwsProperties awsProperties;

    public EcsService(EcsClient ecsClient, AwsProperties awsProperties) {
        this.ecsClient = ecsClient;
        this.awsProperties = awsProperties;
    }

    /**
     * Creates an ECS cluster.
     */
    public CreateClusterResponse createCluster(String clusterName) {
        return ecsClient.createCluster(CreateClusterRequest.builder()
                .clusterName(clusterName)
                .build());
    }

    /**
     * Registers a task definition for container deployment.
     */
    public RegisterTaskDefinitionResponse registerTaskDefinition(
            String family, String containerName, String image, int cpu, int memory) {
        return ecsClient.registerTaskDefinition(RegisterTaskDefinitionRequest.builder()
                .family(family)
                .cpu(String.valueOf(cpu))
                .memory(String.valueOf(memory))
                .containerDefinitions(ContainerDefinition.builder()
                        .name(containerName)
                        .image(image)
                        .cpu(cpu)
                        .memory(memory)
                        .essential(true)
                        .build())
                .build());
    }

    /**
     * Creates an ECS service running a task definition.
     */
    public CreateServiceResponse createService(
            String clusterName, String serviceName, String taskDefinition, int desiredCount) {
        return ecsClient.createService(CreateServiceRequest.builder()
                .cluster(clusterName)
                .serviceName(serviceName)
                .taskDefinition(taskDefinition)
                .desiredCount(desiredCount)
                .launchType(LaunchType.FARGATE)
                .build());
    }

    /**
     * Lists all ECS clusters.
     */
    public ListClustersResponse listClusters() {
        return ecsClient.listClusters(ListClustersRequest.builder().build());
    }

    /**
     * Describes a service's current status.
     */
    public DescribeServicesResponse describeService(String clusterName, String serviceName) {
        return ecsClient.describeServices(DescribeServicesRequest.builder()
                .cluster(clusterName)
                .services(serviceName)
                .build());
    }

    /**
     * Deletes an ECS service.
     */
    public DeleteServiceResponse deleteService(String clusterName, String serviceName) {
        return ecsClient.deleteService(DeleteServiceRequest.builder()
                .cluster(clusterName)
                .service(serviceName)
                .build());
    }
}