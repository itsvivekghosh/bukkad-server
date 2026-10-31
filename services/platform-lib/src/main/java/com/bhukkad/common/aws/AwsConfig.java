package com.bhukkad.common.aws;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sns.SnsClient;
import software.amazon.awssdk.services.secretsmanager.SecretsManagerClient;
import software.amazon.awssdk.services.kms.KmsClient;
import software.amazon.awssdk.services.cloudwatch.CloudWatchClient;
import software.amazon.awssdk.services.identitymanagement.IamClient;
import software.amazon.awssdk.services.s3.S3ClientBuilder;
import software.amazon.awssdk.services.dynamodb.DynamoDbClientBuilder;
import software.amazon.awssdk.services.sqs.SqsClientBuilder;
import software.amazon.awssdk.services.sns.SnsClientBuilder;
import software.amazon.awssdk.services.secretsmanager.SecretsManagerClientBuilder;
import software.amazon.awssdk.services.kms.KmsClientBuilder;
import software.amazon.awssdk.services.cloudwatch.CloudWatchClientBuilder;
import software.amazon.awssdk.services.identitymanagement.IdentityManagementClientBuilder;
import software.amazon.awssdk.services.elasticache.ElastiCacheClient;
import software.amazon.awssdk.services.elasticache.ElastiCacheClientBuilder;
import software.amazon.awssdk.services.lambda.LambdaClient;
import software.amazon.awssdk.services.lambda.LambdaClientBuilder;
import software.amazon.awssdk.services.athena.AthenaClient;
import software.amazon.awssdk.services.athena.AthenaClientBuilder;
import java.net.URI;

/**
 * AWS SDK v2 configuration for Floci-compatible AWS service integration.
 *
 * Creates AWS SDK v2 clients configured to connect to Floci AWS emulator
 * (http://floci:4566) or real AWS based on environment variables.
 *
 * These beans are available to all services that import platform-lib.
 */
@Configuration
@EnableConfigurationProperties(AwsProperties.class)
public class AwsConfig {

    /**
     * Creates an AWS credentials provider using static credentials from
     * AwsProperties (configured via application.yml or environment variables).
     */
    private software.amazon.awssdk.auth.credentials.AwsCredentialsProvider credentials(AwsProperties props) {
        return StaticCredentialsProvider.create(
            AwsBasicCredentials.create(props.getAccessKey(), props.getSecretKey()));
    }

    /**
     * S3 client - for file/blob storage (menu images, restaurant photos, etc.)
     */
    @Bean
    public S3Client s3Client(AwsProperties props) {
        return S3Client.builder()
            .region(Region.of(props.getRegion()))
            .credentialsProvider(credentials(props))
            .endpointOverride(URI.create(props.getEndpoint()))
            .build();
    }

    /**
     * DynamoDB client - for NoSQL data storage (session store, order history,
     * menu item ratings, etc.)
     */
    @Bean
    public DynamoDbClient dynamoDbClient(AwsProperties props) {
        return DynamoDbClient.builder()
            .region(Region.of(props.getRegion()))
            .credentialsProvider(credentials(props))
            .endpointOverride(URI.create(props.getEndpoint()))
            .build();
    }

    /**
     * SQS client - for message queuing (order processing, delivery notifications)
     */
    @Bean
    public SqsClient sqsClient(AwsProperties props) {
        return SqsClient.builder()
            .region(Region.of(props.getRegion()))
            .credentialsProvider(credentials(props))
            .endpointOverride(URI.create(props.getEndpoint()))
            .build();
    }

    /**
     * SNS client - for pub/sub notifications (email, SMS, push notifications)
     */
    @Bean
    public SnsClient snsClient(AwsProperties props) {
        return SnsClient.builder()
            .region(Region.of(props.getRegion()))
            .credentialsProvider(credentials(props))
            .endpointOverride(URI.create(props.getEndpoint()))
            .build();
    }

    /**
     * Secrets Manager client - for secrets management (database passwords,
     * API keys, JWT secrets)
     */
    @Bean
    public SecretsManagerClient secretsManagerClient(AwsProperties props) {
        return SecretsManagerClient.builder()
            .region(Region.of(props.getRegion()))
            .credentialsProvider(credentials(props))
            .endpointOverride(URI.create(props.getEndpoint()))
            .build();
    }

    /**
     * KMS client - for encryption key management (data encryption at rest)
     */
    @Bean
    public KmsClient kmsClient(AwsProperties props) {
        return KmsClient.builder()
            .region(Region.of(props.getRegion()))
            .credentialsProvider(credentials(props))
            .endpointOverride(URI.create(props.getEndpoint()))
            .build();
    }

    /**
     * CloudWatch client - for metrics and monitoring
     */
    @Bean
    public CloudWatchClient cloudWatchClient(AwsProperties props) {
        return CloudWatchClient.builder()
            .region(Region.of(props.getRegion()))
            .credentialsProvider(credentials(props))
            .endpointOverride(URI.create(props.getEndpoint()))
            .build();
    }

    /**
     * IAM client - for identity and access management
     */
    @Bean
    public software.amazon.awssdk.services.identitymanagement.IamClient iamClient(AwsProperties props) {
        return IdentityManagementClientBuilder.create()
            .region(Region.of(props.getRegion()))
            .credentialsProvider(credentials(props))
            .endpointOverride(URI.create(props.getEndpoint()))
            .build();
    }

    /**
     * ElastiCache client - for Redis-compatible caching (session store)
     */
    @Bean
    public ElastiCacheClient elasticacheClient(AwsProperties props) {
        return ElastiCacheClient.builder()
            .region(Region.of(props.getRegion()))
            .credentialsProvider(credentials(props))
            .endpointOverride(URI.create(props.getEndpoint()))
            .build();
    }

    /**
     * Lambda client - for serverless image processing, webhook handlers
     */
    @Bean
    public LambdaClient lambdaClient(AwsProperties props) {
        return LambdaClient.builder()
            .region(Region.of(props.getRegion()))
            .credentialsProvider(credentials(props))
            .endpointOverride(URI.create(props.getEndpoint()))
            .build();
    }

    /**
     * Athena client - for ad-hoc analytics on S3 data
     */
    @Bean
    public AthenaClient athenaClient(AwsProperties props) {
        return AthenaClient.builder()
            .region(Region.of(props.getRegion()))
            .credentialsProvider(credentials(props))
            .endpointOverride(URI.create(props.getEndpoint()))
            .build();
    }
}
