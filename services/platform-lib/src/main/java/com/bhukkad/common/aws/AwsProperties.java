package com.bhukkad.common.aws;

import org.springframework.boot.context.properties.ConfigurationProperties;
import lombok.Data;

/**
 * AWS configuration properties for Floci-compatible AWS service integration.
 *
 * These properties are configured in each service's application.yml under
 * the {@code aws} and {@code spring.cloud.aws} prefixes.
 */
@Data
@ConfigurationProperties(prefix = "aws")
public class AwsProperties {

    /** AWS region (e.g., us-east-1) */
    private String region = "us-east-1";

    /** AWS access key ID */
    private String accessKey = "test";

    /** AWS secret access key */
    private String secretKey = "test";

    /** AWS service endpoint (e.g., http://floci:4566 for local development) */
    private String endpoint = "http://floci:4566";

    /** S3 bucket name */
    private String s3Bucket = "bhukkad-assets";

    /** S3 Express One Zone bucket name (for ultra-low latency) */
    private String s3ExpressBucket = "bhukkad-assets-express";

    /** S3 Express One Zone availability zone ID */
    private String s3ExpressAvailabilityZone = "";

    public String getS3ExpressBucket() {
        return s3ExpressBucket;
    }

    public void setS3ExpressBucket(String s3ExpressBucket) {
        this.s3ExpressBucket = s3ExpressBucket;
    }

    public String getS3ExpressAvailabilityZone() {
        return s3ExpressAvailabilityZone;
    }

    public void setS3ExpressAvailabilityZone(String s3ExpressAvailabilityZone) {
        this.s3ExpressAvailabilityZone = s3ExpressAvailabilityZone;
    }

    /** DynamoDB table name prefix */
    private String dynamodbTablePrefix = "bhukkad-";

    /** DynamoDB billing mode (PROVISIONED or PAY_PER_REQUEST for On-Demand) */
    private String dynamodbBillingMode = "PAY_PER_REQUEST";

    public String getDynamodbBillingMode() {
        return dynamodbBillingMode;
    }

    public void setDynamodbBillingMode(String dynamodbBillingMode) {
        this.dynamodbBillingMode = dynamodbBillingMode;
    }

    /** SQS queue name prefix */
    private String sqsQueuePrefix = "bhukkad-";

    /** SNS topic name prefix */
    private String snsTopicPrefix = "bhukkad-";

    /** Kinesis stream name prefix */
    private String kinesisStreamPrefix = "bhukkad-";

    public String getKinesisStreamPrefix() {
        return kinesisStreamPrefix;
    }

    public void setKinesisStreamPrefix(String kinesisStreamPrefix) {
        this.kinesisStreamPrefix = kinesisStreamPrefix;
    }

    /** Secrets Manager secret name prefix */
    private String secretsManagerPrefix = "bhukkad/";

    /** KMS key alias */
    private String kmsKeyAlias = "bhukkad-master-key";

    /** DAX cluster endpoint (for production) */
    private String daxEndpoint = "";

    /** AWS Account ID (for ARN construction) */
    private String accountId = "123456789012";

    /** Environment name (dev, staging, prod) */
    private String environment = "dev";

    public String getEnvironment() {
        return environment;
    }

    public void setEnvironment(String environment) {
        this.environment = environment;
    }

    public String getAccountId() {
        return accountId;
    }

    public void setAccountId(String accountId) {
        this.accountId = accountId;
    }

    public String getDaxEndpoint() {
        return daxEndpoint;
    }

    public void setDaxEndpoint(String daxEndpoint) {
        this.daxEndpoint = daxEndpoint;
    }

    public String getS3Bucket() {
        return s3Bucket;
    }

    public void setS3Bucket(String s3Bucket) {
        this.s3Bucket = s3Bucket;
    }

    public String getDynamodbTablePrefix() {
        return dynamodbTablePrefix;
    }

    public void setDynamodbTablePrefix(String dynamodbTablePrefix) {
        this.dynamodbTablePrefix = dynamodbTablePrefix;
    }

    public String getSqsQueuePrefix() {
        return sqsQueuePrefix;
    }

    public void setSqsQueuePrefix(String sqsQueuePrefix) {
        this.sqsQueuePrefix = sqsQueuePrefix;
    }

    public String getSnsTopicPrefix() {
        return snsTopicPrefix;
    }

    public void setSnsTopicPrefix(String snsTopicPrefix) {
        this.snsTopicPrefix = snsTopicPrefix;
    }

    public String getSecretsManagerPrefix() {
        return secretsManagerPrefix;
    }

    public void setSecretsManagerPrefix(String secretsManagerPrefix) {
        this.secretsManagerPrefix = secretsManagerPrefix;
    }

    public String getKmsKeyAlias() {
        return kmsKeyAlias;
    }

    public void setKmsKeyAlias(String kmsKeyAlias) {
        this.kmsKeyAlias = kmsKeyAlias;
    }

    public String getRegion() {
        return region;
    }

    public void setRegion(String region) {
        this.region = region;
    }

    public String getAccessKey() {
        return accessKey;
    }

    public void setAccessKey(String accessKey) {
        this.accessKey = accessKey;
    }

    public String getSecretKey() {
        return secretKey;
    }

    public void setSecretKey(String secretKey) {
        this.secretKey = secretKey;
    }

    public String getEndpoint() {
        return endpoint;
    }

    public void setEndpoint(String endpoint) {
        this.endpoint = endpoint;
    }
}
