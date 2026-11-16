package com.bhukkad.common.aws;

import org.springframework.stereotype.Service;
import software.amazon.awssdk.services.elasticache.ElastiCacheClient;
import software.amazon.awssdk.services.elasticache.model.*;

/**
 * AWS ElastiCache service for Redis caching layer.
 * <p>
 * Provides operations for managing ElastiCache Redis clusters used for
 * caching frequently accessed data (menu items, restaurant details,
 * user sessions).
 * <p>
 * Use cases in Bhukkad:
 * - Cache restaurant details and menu items to reduce database load
 * - Cache user session data for fast authentication checks
 * - Cache popular search results for faster page loads
 * - Distributed rate limiting counters
 * <p>
 * Fully testable against Floci emulator locally.
 */
@Service
public class ElastiCacheService {

    private final ElastiCacheClient elasticacheClient;
    private final AwsProperties awsProperties;

    public ElastiCacheService(ElastiCacheClient elasticacheClient, AwsProperties awsProperties) {
        this.elasticacheClient = elasticacheClient;
        this.awsProperties = awsProperties;
    }

    /**
     * Creates a Redis cache cluster.
     */
    public CreateCacheClusterResponse createCacheCluster(
            String clusterId, String nodeType, int numCacheNodes,
            String engine, String engineVersion) {
        return elasticacheClient.createCacheCluster(CreateCacheClusterRequest.builder()
                .cacheClusterId(clusterId)
                .cacheNodeType(nodeType)
                .numCacheNodes(numCacheNodes)
                .engine(engine)
                .engineVersion(engineVersion)
                .build());
    }

    /**
     * Describes all ElastiCache clusters.
     */
    public DescribeCacheClustersResponse describeCacheClusters() {
        return elasticacheClient.describeCacheClusters(DescribeCacheClustersRequest.builder().build());
    }

    /**
     * Describes a specific cache cluster.
     */
    public DescribeCacheClustersResponse describeCacheCluster(String clusterId) {
        return elasticacheClient.describeCacheClusters(DescribeCacheClustersRequest.builder()
                .cacheClusterId(clusterId)
                .build());
    }

    /**
     * Deletes a cache cluster.
     */
    public DeleteCacheClusterResponse deleteCacheCluster(String clusterId) {
        return elasticacheClient.deleteCacheCluster(DeleteCacheClusterRequest.builder()
                .cacheClusterId(clusterId)
                .build());
    }

    /**
     * Lists all cache parameter groups.
     */
    public DescribeCacheParameterGroupsResponse describeCacheParameterGroups() {
        return elasticacheClient.describeCacheParameterGroups(DescribeCacheParameterGroupsRequest.builder().build());
    }

    /**
     * Lists all cache subnet groups.
     */
    public DescribeCacheSubnetGroupsResponse describeCacheSubnetGroups() {
        return elasticacheClient.describeCacheSubnetGroups(DescribeCacheSubnetGroupsRequest.builder().build());
    }

    /**
     * Lists all replication groups.
     */
    public DescribeReplicationGroupsResponse describeReplicationGroups() {
        return elasticacheClient.describeReplicationGroups(DescribeReplicationGroupsRequest.builder().build());
    }
}