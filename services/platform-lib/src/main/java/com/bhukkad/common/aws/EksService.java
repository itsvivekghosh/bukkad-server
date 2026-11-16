package com.bhukkad.common.aws;

import org.springframework.stereotype.Service;
import software.amazon.awssdk.services.eks.EksClient;
import software.amazon.awssdk.services.eks.model.*;

/**
 * EKS service for Kubernetes cluster management.
 * <p>
 * Provides operations for managing EKS clusters and node groups.
 * Fully testable against Floci emulator locally.
 */
@Service
public class EksService {

    private final EksClient eksClient;
    private final AwsProperties awsProperties;

    public EksService(EksClient eksClient, AwsProperties awsProperties) {
        this.eksClient = eksClient;
        this.awsProperties = awsProperties;
    }

    /**
     * Creates an EKS cluster.
     */
    public CreateClusterResponse createCluster(String clusterName, String roleArn, String subnets) {
        return eksClient.createCluster(CreateClusterRequest.builder()
                .name(clusterName)
                .roleArn(roleArn)
                .resourcesVpcConfig(VpcConfigRequest.builder()
                        .subnetIds(subnets.split(","))
                        .build())
                .build());
    }

    /**
     * Creates a node group for an EKS cluster.
     */
    public CreateNodegroupResponse createNodegroup(
            String clusterName, String nodegroupName, String nodeRole, String subnets) {
        return eksClient.createNodegroup(CreateNodegroupRequest.builder()
                .clusterName(clusterName)
                .nodegroupName(nodegroupName)
                .nodeRole(nodeRole)
                .subnets(subnets.split(","))
                .build());
    }

    /**
     * Lists all EKS clusters.
     */
    public ListClustersResponse listClusters() {
        return eksClient.listClusters(ListClustersRequest.builder().build());
    }

    /**
     * Describes an EKS cluster configuration.
     */
    public DescribeClusterResponse describeCluster(String clusterName) {
        return eksClient.describeCluster(DescribeClusterRequest.builder()
                .name(clusterName)
                .build());
    }

    /**
     * Deletes an EKS cluster.
     */
    public DeleteClusterResponse deleteCluster(String clusterName) {
        return eksClient.deleteCluster(DeleteClusterRequest.builder()
                .name(clusterName)
                .build());
    }
}