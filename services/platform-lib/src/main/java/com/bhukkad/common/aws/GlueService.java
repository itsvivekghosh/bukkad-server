package com.bhukkad.common.aws;

import org.springframework.stereotype.Service;
import software.amazon.awssdk.services.glue.GlueClient;
import software.amazon.awssdk.services.glue.model.*;

/**
 * Glue service for ETL and data catalog management.
 * <p>
 * Provides operations for managing Glue databases, tables, and crawlers
 * for ETL workflows. Fully testable against Floci emulator locally.
 */
@Service
public class GlueService {

    private final GlueClient glueClient;
    private final AwsProperties awsProperties;

    public GlueService(GlueClient glueClient, AwsProperties awsProperties) {
        this.glueClient = glueClient;
        this.awsProperties = awsProperties;
    }

    /**
     * Creates a Glue database.
     */
    public CreateDatabaseResponse createDatabase(String databaseName) {
        return glueClient.createDatabase(CreateDatabaseRequest.builder()
                .databaseInput(DatabaseInput.builder()
                        .name(databaseName)
                        .build())
                .build());
    }

    /**
     * Creates a Glue crawler for discovering data schema.
     */
    public CreateCrawlerResponse createCrawler(
            String crawlerName, String role, String databaseName, String s3Path) {
        return glueClient.createCrawler(CreateCrawlerRequest.builder()
                .name(crawlerName)
                .role(role)
                .databaseName(databaseName)
                .targets(CrawlerTargets.builder()
                        .s3Targets(S3Target.builder()
                                .path(s3Path)
                                .build())
                        .build())
                .build());
    }

    /**
     * Starts a Glue crawler run.
     */
    public StartCrawlerResponse startCrawler(String crawlerName) {
        return glueClient.startCrawler(StartCrawlerRequest.builder()
                .name(crawlerName)
                .build());
    }

    /**
     * Lists all Glue databases.
     */
    public GetDatabasesResponse listDatabases() {
        return glueClient.getDatabases(GetDatabasesRequest.builder().build());
    }

    /**
     * Gets a specific database metadata.
     */
    public GetDatabaseResponse getDatabase(String databaseName) {
        return glueClient.getDatabase(GetDatabaseRequest.builder()
                .name(databaseName)
                .build());
    }
}