package com.bhukkad.common.aws;

import org.springframework.stereotype.Service;
import software.amazon.awssdk.services.pipes.PipesClient;
import software.amazon.awssdk.services.pipes.model.*;

/**
 * AWS EventBridge Pipes service for event routing between services.
 * <p>
 * Provides operations for creating and managing event pipes that connect
 * event producers to consumers with built-in filtering, enrichment, and
 * transformation capabilities.
 * <p>
 * Use cases in Bhukkad:
 * - Route order events from commerce service to engagement service
 *   for notifications and social feed updates
 * - Route payment events from commerce to identity service for
 *   transaction record updates
 * - Route delivery events from commerce to engagement for agent earnings
 * - Route catalog updates from catalog to search service for
 *   OpenSearch index updates
 * <p>
 * Fully testable against Floci emulator locally.
 */
@Service
public class PipesService {

    private final PipesClient pipesClient;
    private final AwsProperties awsProperties;

    public PipesService(PipesClient pipesClient, AwsProperties awsProperties) {
        this.pipesClient = pipesClient;
        this.awsProperties = awsProperties;
    }

    /**
     * Lists all event pipes.
     */
    public ListPipesResponse listPipes() {
        return pipesClient.listPipes(ListPipesRequest.builder().build());
    }

    /**
     * Describes a specific pipe.
     */
    public DescribePipeResponse describePipe(String pipeName) {
        return pipesClient.describePipe(DescribePipeRequest.builder()
                .name(pipeName)
                .build());
    }
}