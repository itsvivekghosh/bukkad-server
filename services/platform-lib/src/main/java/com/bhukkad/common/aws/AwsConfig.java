package com.bhukkad.common.aws;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.http.SdkHttpClient;
import software.amazon.awssdk.http.apache.ApacheHttpClient;
import software.amazon.awssdk.http.async.SdkAsyncHttpClient;
import software.amazon.awssdk.http.nio.netty.NettyNioAsyncHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.appsync.AppSyncClient;
import software.amazon.awssdk.services.athena.AthenaClient;
import software.amazon.awssdk.services.applicationautoscaling.ApplicationAutoScalingClient;
import software.amazon.awssdk.services.backup.BackupClient;
import software.amazon.awssdk.services.cloudformation.CloudFormationClient;
import software.amazon.awssdk.services.cloudfront.CloudFrontClient;
import software.amazon.awssdk.services.cloudtrail.CloudTrailClient;
import software.amazon.awssdk.services.cloudwatch.CloudWatchClient;
import software.amazon.awssdk.services.cloudwatchlogs.CloudWatchLogsClient;
import software.amazon.awssdk.services.cognitoidentityprovider.CognitoIdentityProviderClient;
import software.amazon.awssdk.services.comprehend.ComprehendClient;
import software.amazon.awssdk.services.config.ConfigClient;
import software.amazon.awssdk.services.dax.DaxClient;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.docdb.DocDbClient;
import software.amazon.awssdk.services.ec2.Ec2Client;
import software.amazon.awssdk.services.ecs.EcsClient;
import software.amazon.awssdk.services.eks.EksClient;
import software.amazon.awssdk.services.elasticache.ElastiCacheClient;
import software.amazon.awssdk.services.emr.EmrClient;
import software.amazon.awssdk.services.eventbridge.EventBridgeClient;
import software.amazon.awssdk.services.firehose.FirehoseClient;
import software.amazon.awssdk.services.glue.GlueClient;
import software.amazon.awssdk.services.guardduty.GuardDutyClient;
import software.amazon.awssdk.services.iam.IamClient;
import software.amazon.awssdk.services.kinesis.KinesisClient;
import software.amazon.awssdk.services.kms.KmsClient;
import software.amazon.awssdk.services.lambda.LambdaClient;
import software.amazon.awssdk.services.neptune.NeptuneClient;
import software.amazon.awssdk.services.opensearchserverless.OpenSearchServerlessClient;
import software.amazon.awssdk.services.pipes.PipesClient;
import software.amazon.awssdk.services.polly.PollyClient;
import software.amazon.awssdk.services.rds.RdsClient;
import software.amazon.awssdk.services.redshift.RedshiftClient;
import software.amazon.awssdk.services.rekognition.RekognitionClient;
import software.amazon.awssdk.services.route53.Route53Client;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3AsyncClient;
import software.amazon.awssdk.services.secretsmanager.SecretsManagerClient;
import software.amazon.awssdk.services.securityhub.SecurityHubClient;
import software.amazon.awssdk.services.servicecatalog.ServiceCatalogClient;
import software.amazon.awssdk.services.ses.SesClient;
import software.amazon.awssdk.services.sesv2.SesV2Client;
import software.amazon.awssdk.services.sfn.SfnClient;
import software.amazon.awssdk.services.sns.SnsClient;
import software.amazon.awssdk.services.sns.SnsClient;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.ssm.SsmClient;
import software.amazon.awssdk.services.textract.TextractClient;
import software.amazon.awssdk.services.translate.TranslateClient;
import software.amazon.awssdk.services.wafv2.Wafv2Client;
import software.amazon.awssdk.services.xray.XRayClient;
import software.amazon.awssdk.transfer.s3.S3TransferManager;

import java.net.URI;
import java.time.Duration;
import java.util.concurrent.Executors;

/**
 * AWS SDK v2 client configuration for Floci AWS Emulator.
 * <p>
 * Creates all AWS service clients configured to connect to the Floci emulator
 * at {@code http://floci:4566} for local development and testing.
 * In production, the endpoint should be overridden to use real AWS service endpoints.
 */
@Configuration
@EnableConfigurationProperties(AwsProperties.class)
public class AwsConfig {

    private StaticCredentialsProvider credentials(AwsProperties props) {
        return StaticCredentialsProvider.create(
                AwsBasicCredentials.create(props.getAccessKey(), props.getSecretKey()));
    }

    private SdkHttpClient httpClient() {
        return ApacheHttpClient.builder()
                .maxConnections(100)
                .connectionTimeout(Duration.ofSeconds(5))
                .socketTimeout(Duration.ofSeconds(30))
                .connectionAcquisitionTimeout(Duration.ofSeconds(10))
                .build();
    }

    private SdkAsyncHttpClient asyncHttpClient() {
        return NettyNioAsyncHttpClient.builder()
                .maxConcurrency(100)
                .connectionTimeout(Duration.ofSeconds(5))
                .readTimeout(Duration.ofSeconds(30))
                .writeTimeout(Duration.ofSeconds(30))
                .build();
    }

    @Bean
    public S3Client s3Client(AwsProperties props) {
        return S3Client.builder()
                .region(Region.of(props.getRegion()))
                .credentialsProvider(credentials(props))
                .endpointOverride(URI.create(props.getEndpoint()))
                .httpClient(httpClient())
                .build();
    }

    @Bean
    public S3AsyncClient s3AsyncClient(AwsProperties props) {
        return S3AsyncClient.builder()
                .region(Region.of(props.getRegion()))
                .credentialsProvider(credentials(props))
                .endpointOverride(URI.create(props.getEndpoint()))
                .httpClient(asyncHttpClient())
                .build();
    }

    @Bean
    public S3TransferManager s3TransferManager(S3AsyncClient s3AsyncClient) {
        return S3TransferManager.builder()
                .s3Client(s3AsyncClient)
                .build();
    }

    @Bean
    public DynamoDbClient dynamoDbClient(AwsProperties props) {
        return DynamoDbClient.builder()
                .region(Region.of(props.getRegion()))
                .credentialsProvider(credentials(props))
                .endpointOverride(URI.create(props.getEndpoint()))
                .httpClient(httpClient())
                .build();
    }

    @Bean
    public DaxClient daxClient(AwsProperties props) {
        if (props.getEndpoint().contains("floci") || props.getEndpoint().contains("localhost")) {
            return null;
        }
        return DaxClient.builder()
                .region(Region.of(props.getRegion()))
                .credentialsProvider(credentials(props))
                .endpointOverride(URI.create(props.getDaxEndpoint()))
                .httpClient(httpClient())
                .build();
    }

    @Bean
    public SqsClient sqsClient(AwsProperties props) {
        return SqsClient.builder()
                .region(Region.of(props.getRegion()))
                .credentialsProvider(credentials(props))
                .endpointOverride(URI.create(props.getEndpoint()))
                .httpClient(httpClient())
                .build();
    }

    @Bean
    public SnsClient snsClient(AwsProperties props) {
        return SnsClient.builder()
                .region(Region.of(props.getRegion()))
                .credentialsProvider(credentials(props))
                .endpointOverride(URI.create(props.getEndpoint()))
                .httpClient(httpClient())
                .build();
    }

    @Bean
    public SecretsManagerClient secretsManagerClient(AwsProperties props) {
        return SecretsManagerClient.builder()
                .region(Region.of(props.getRegion()))
                .credentialsProvider(credentials(props))
                .endpointOverride(URI.create(props.getEndpoint()))
                .httpClient(httpClient())
                .build();
    }

    @Bean
    public KmsClient kmsClient(AwsProperties props) {
        return KmsClient.builder()
                .region(Region.of(props.getRegion()))
                .credentialsProvider(credentials(props))
                .endpointOverride(URI.create(props.getEndpoint()))
                .httpClient(httpClient())
                .build();
    }

    @Bean
    public CloudWatchClient cloudWatchClient(AwsProperties props) {
        return CloudWatchClient.builder()
                .region(Region.of(props.getRegion()))
                .credentialsProvider(credentials(props))
                .endpointOverride(URI.create(props.getEndpoint()))
                .httpClient(httpClient())
                .build();
    }

    @Bean
    public EventBridgeClient eventBridgeClient(AwsProperties props) {
        return EventBridgeClient.builder()
                .region(Region.of(props.getRegion()))
                .credentialsProvider(credentials(props))
                .endpointOverride(URI.create(props.getEndpoint()))
                .httpClient(httpClient())
                .build();
    }

    @Bean
    public IamClient iamClient(AwsProperties props) {
        return IamClient.builder()
                .region(Region.of(props.getRegion()))
                .credentialsProvider(credentials(props))
                .endpointOverride(URI.create(props.getEndpoint()))
                .httpClient(httpClient())
                .build();
    }

    @Bean
    public ElastiCacheClient elasticacheClient(AwsProperties props) {
        return ElastiCacheClient.builder()
                .region(Region.of(props.getRegion()))
                .credentialsProvider(credentials(props))
                .endpointOverride(URI.create(props.getEndpoint()))
                .httpClient(httpClient())
                .build();
    }

    @Bean
    public LambdaClient lambdaClient(AwsProperties props) {
        return LambdaClient.builder()
                .region(Region.of(props.getRegion()))
                .credentialsProvider(credentials(props))
                .endpointOverride(URI.create(props.getEndpoint()))
                .httpClient(httpClient())
                .build();
    }

    @Bean
    public AthenaClient athenaClient(AwsProperties props) {
        return AthenaClient.builder()
                .region(Region.of(props.getRegion()))
                .credentialsProvider(credentials(props))
                .endpointOverride(URI.create(props.getEndpoint()))
                .httpClient(httpClient())
                .build();
    }

    @Bean
    public SfnClient sfnClient(AwsProperties props) {
        return SfnClient.builder()
                .region(Region.of(props.getRegion()))
                .credentialsProvider(credentials(props))
                .endpointOverride(URI.create(props.getEndpoint()))
                .httpClient(httpClient())
                .build();
    }

    @Bean
    public KinesisClient kinesisClient(AwsProperties props) {
        return KinesisClient.builder()
                .region(Region.of(props.getRegion()))
                .credentialsProvider(credentials(props))
                .endpointOverride(URI.create(props.getEndpoint()))
                .httpClient(httpClient())
                .build();
    }

    @Bean
    public OpenSearchServerlessClient openSearchServerlessClient(AwsProperties props) {
        return OpenSearchServerlessClient.builder()
                .region(Region.of(props.getRegion()))
                .credentialsProvider(credentials(props))
                .endpointOverride(URI.create(props.getEndpoint()))
                .httpClient(httpClient())
                .build();
    }

    @Bean
    public AppSyncClient appSyncClient(AwsProperties props) {
        return AppSyncClient.builder()
                .region(Region.of(props.getRegion()))
                .credentialsProvider(credentials(props))
                .endpointOverride(URI.create(props.getEndpoint()))
                .httpClient(httpClient())
                .build();
    }

    @Bean
    public PipesClient pipesClient(AwsProperties props) {
        return PipesClient.builder()
                .region(Region.of(props.getRegion()))
                .credentialsProvider(credentials(props))
                .endpointOverride(URI.create(props.getEndpoint()))
                .httpClient(httpClient())
                .build();
    }

    @Bean
    public CloudFrontClient cloudFrontClient(AwsProperties props) {
        return CloudFrontClient.builder()
                .region(Region.of(props.getRegion()))
                .credentialsProvider(credentials(props))
                .endpointOverride(URI.create(props.getEndpoint()))
                .httpClient(httpClient())
                .build();
    }

    @Bean
    public CognitoIdentityProviderClient cognitoIdentityProviderClient(AwsProperties props) {
        return CognitoIdentityProviderClient.builder()
                .region(Region.of(props.getRegion()))
                .credentialsProvider(credentials(props))
                .endpointOverride(URI.create(props.getEndpoint()))
                .httpClient(httpClient())
                .build();
    }

    @Bean
    public Wafv2Client wafv2Client(AwsProperties props) {
        return Wafv2Client.builder()
                .region(Region.of(props.getRegion()))
                .credentialsProvider(credentials(props))
                .endpointOverride(URI.create(props.getEndpoint()))
                .httpClient(httpClient())
                .build();
    }

    @Bean
    public CloudTrailClient cloudTrailClient(AwsProperties props) {
        return CloudTrailClient.builder()
                .region(Region.of(props.getRegion()))
                .credentialsProvider(credentials(props))
                .endpointOverride(URI.create(props.getEndpoint()))
                .httpClient(httpClient())
                .build();
    }

    @Bean
    public CloudFormationClient cloudFormationClient(AwsProperties props) {
        return CloudFormationClient.builder()
                .region(Region.of(props.getRegion()))
                .credentialsProvider(credentials(props))
                .endpointOverride(URI.create(props.getEndpoint()))
                .httpClient(httpClient())
                .build();
    }

    @Bean
    public EcsClient ecsClient(AwsProperties props) {
        return EcsClient.builder()
                .region(Region.of(props.getRegion()))
                .credentialsProvider(credentials(props))
                .endpointOverride(URI.create(props.getEndpoint()))
                .httpClient(httpClient())
                .build();
    }

    @Bean
    public EksClient eksClient(AwsProperties props) {
        return EksClient.builder()
                .region(Region.of(props.getRegion()))
                .credentialsProvider(credentials(props))
                .endpointOverride(URI.create(props.getEndpoint()))
                .httpClient(httpClient())
                .build();
    }

    @Bean
    public RdsClient rdsClient(AwsProperties props) {
        return RdsClient.builder()
                .region(Region.of(props.getRegion()))
                .credentialsProvider(credentials(props))
                .endpointOverride(URI.create(props.getEndpoint()))
                .httpClient(httpClient())
                .build();
    }

    @Bean
    public GlueClient glueClient(AwsProperties props) {
        return GlueClient.builder()
                .region(Region.of(props.getRegion()))
                .credentialsProvider(credentials(props))
                .endpointOverride(URI.create(props.getEndpoint()))
                .httpClient(httpClient())
                .build();
    }

    @Bean
    public FirehoseClient firehoseClient(AwsProperties props) {
        return FirehoseClient.builder()
                .region(Region.of(props.getRegion()))
                .credentialsProvider(credentials(props))
                .endpointOverride(URI.create(props.getEndpoint()))
                .httpClient(httpClient())
                .build();
    }

    @Bean
    public Route53Client route53Client(AwsProperties props) {
        return Route53Client.builder()
                .region(Region.of(props.getRegion()))
                .credentialsProvider(credentials(props))
                .endpointOverride(URI.create(props.getEndpoint()))
                .httpClient(httpClient())
                .build();
    }

    @Bean
    public Ec2Client ec2Client(AwsProperties props) {
        return Ec2Client.builder()
                .region(Region.of(props.getRegion()))
                .credentialsProvider(credentials(props))
                .endpointOverride(URI.create(props.getEndpoint()))
                .httpClient(httpClient())
                .build();
    }

    @Bean
    public ConfigClient configClient(AwsProperties props) {
        return ConfigClient.builder()
                .region(Region.of(props.getRegion()))
                .credentialsProvider(credentials(props))
                .endpointOverride(URI.create(props.getEndpoint()))
                .httpClient(httpClient())
                .build();
    }

    @Bean
    public GuardDutyClient guardDutyClient(AwsProperties props) {
        return GuardDutyClient.builder()
                .region(Region.of(props.getRegion()))
                .credentialsProvider(credentials(props))
                .endpointOverride(URI.create(props.getEndpoint()))
                .httpClient(httpClient())
                .build();
    }

    @Bean
    public SecurityHubClient securityHubClient(AwsProperties props) {
        return SecurityHubClient.builder()
                .region(Region.of(props.getRegion()))
                .credentialsProvider(credentials(props))
                .endpointOverride(URI.create(props.getEndpoint()))
                .httpClient(httpClient())
                .build();
    }

    @Bean
    public BackupClient backupClient(AwsProperties props) {
        return BackupClient.builder()
                .region(Region.of(props.getRegion()))
                .credentialsProvider(credentials(props))
                .endpointOverride(URI.create(props.getEndpoint()))
                .httpClient(httpClient())
                .build();
    }

    @Bean
    public ApplicationAutoScalingClient applicationAutoScalingClient(AwsProperties props) {
        return ApplicationAutoScalingClient.builder()
                .region(Region.of(props.getRegion()))
                .credentialsProvider(credentials(props))
                .endpointOverride(URI.create(props.getEndpoint()))
                .httpClient(httpClient())
                .build();
    }

    @Bean
    public ServiceCatalogClient serviceCatalogClient(AwsProperties props) {
        return ServiceCatalogClient.builder()
                .region(Region.of(props.getRegion()))
                .credentialsProvider(credentials(props))
                .endpointOverride(URI.create(props.getEndpoint()))
                .httpClient(httpClient())
                .build();
    }

    // ==================== SES (Simple Email Service) ====================

    @Bean
    public SesClient sesClient(AwsProperties props) {
        return SesClient.builder()
                .region(Region.of(props.getRegion()))
                .credentialsProvider(credentials(props))
                .endpointOverride(URI.create(props.getEndpoint()))
                .httpClient(httpClient())
                .build();
    }

    @Bean
    public SesV2Client sesV2Client(AwsProperties props) {
        return SesV2Client.builder()
                .region(Region.of(props.getRegion()))
                .credentialsProvider(credentials(props))
                .endpointOverride(URI.create(props.getEndpoint()))
                .httpClient(httpClient())
                .build();
    }

    // ==================== SSM (Systems Manager Parameter Store) ====================

    @Bean
    public SsmClient ssmClient(AwsProperties props) {
        return SsmClient.builder()
                .region(Region.of(props.getRegion()))
                .credentialsProvider(credentials(props))
                .endpointOverride(URI.create(props.getEndpoint()))
                .httpClient(httpClient())
                .build();
    }

    // ==================== X-Ray (Distributed Tracing) ====================

    @Bean
    public XRayClient xrayClient(AwsProperties props) {
        return XRayClient.builder()
                .region(Region.of(props.getRegion()))
                .credentialsProvider(credentials(props))
                .endpointOverride(URI.create(props.getEndpoint()))
                .httpClient(httpClient())
                .build();
    }

    // ==================== Rekognition (Image/Video Analysis) ====================

    @Bean
    public RekognitionClient rekognitionClient(AwsProperties props) {
        return RekognitionClient.builder()
                .region(Region.of(props.getRegion()))
                .credentialsProvider(credentials(props))
                .endpointOverride(URI.create(props.getEndpoint()))
                .httpClient(httpClient())
                .build();
    }

    // ==================== Textract (Document Text Analysis) ====================

    @Bean
    public TextractClient textractClient(AwsProperties props) {
        return TextractClient.builder()
                .region(Region.of(props.getRegion()))
                .credentialsProvider(credentials(props))
                .endpointOverride(URI.create(props.getEndpoint()))
                .httpClient(httpClient())
                .build();
    }

    // ==================== Polly (Text-to-Speech) ====================

    @Bean
    public PollyClient pollyClient(AwsProperties props) {
        return PollyClient.builder()
                .region(Region.of(props.getRegion()))
                .credentialsProvider(credentials(props))
                .endpointOverride(URI.create(props.getEndpoint()))
                .httpClient(httpClient())
                .build();
    }

    // ==================== Translate (Language Translation) ====================

    @Bean
    public TranslateClient translateClient(AwsProperties props) {
        return TranslateClient.builder()
                .region(Region.of(props.getRegion()))
                .credentialsProvider(credentials(props))
                .endpointOverride(URI.create(props.getEndpoint()))
                .httpClient(httpClient())
                .build();
    }

    // ==================== Comprehend (Natural Language Processing) ====================

    @Bean
    public ComprehendClient comprehendClient(AwsProperties props) {
        return ComprehendClient.builder()
                .region(Region.of(props.getRegion()))
                .credentialsProvider(credentials(props))
                .endpointOverride(URI.create(props.getEndpoint()))
                .httpClient(httpClient())
                .build();
    }

    // ==================== Redshift (Data Warehouse) ====================

    @Bean
    public RedshiftClient redshiftClient(AwsProperties props) {
        return RedshiftClient.builder()
                .region(Region.of(props.getRegion()))
                .credentialsProvider(credentials(props))
                .endpointOverride(URI.create(props.getEndpoint()))
                .httpClient(httpClient())
                .build();
    }

    // ==================== DocumentDB (Document Database) ====================

    @Bean
    public DocDbClient documentDbClient(AwsProperties props) {
        return DocDbClient.builder()
                .region(Region.of(props.getRegion()))
                .credentialsProvider(credentials(props))
                .endpointOverride(URI.create(props.getEndpoint()))
                .httpClient(httpClient())
                .build();
    }

    // ==================== Neptune (Graph Database) ====================

    @Bean
    public NeptuneClient neptuneClient(AwsProperties props) {
        return NeptuneClient.builder()
                .region(Region.of(props.getRegion()))
                .credentialsProvider(credentials(props))
                .endpointOverride(URI.create(props.getEndpoint()))
                .httpClient(httpClient())
                .build();
    }

    // ==================== EMR (Elastic MapReduce) ====================

    @Bean
    public EmrClient emrClient(AwsProperties props) {
        return EmrClient.builder()
                .region(Region.of(props.getRegion()))
                .credentialsProvider(credentials(props))
                .endpointOverride(URI.create(props.getEndpoint()))
                .httpClient(httpClient())
                .build();
    }

    // ==================== CloudWatch Logs (Logging) ====================

    @Bean
    public CloudWatchLogsClient cloudWatchLogsClient(AwsProperties props) {
        return CloudWatchLogsClient.builder()
                .region(Region.of(props.getRegion()))
                .credentialsProvider(credentials(props))
                .endpointOverride(URI.create(props.getEndpoint()))
                .httpClient(httpClient())
                .build();
    }
}