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

    /** DynamoDB table name prefix */
    private String dynamodbTablePrefix = "bhukkad-";

    /** SQS queue name prefix */
    private String sqsQueuePrefix = "bhukkad-";

    /** SNS topic name prefix */
    private String snsTopicPrefix = "bhukkad-";

    /** Secrets Manager secret name prefix */
    private String secretsManagerPrefix = "bhukkad/";

    /** KMS key alias */
    private String kmsKeyAlias = "bhukkad-master-key";

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
