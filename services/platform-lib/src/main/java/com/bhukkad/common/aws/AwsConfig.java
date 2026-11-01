package com.bhukkad.common.aws;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * AWS SDK v2 configuration for Floci-compatible AWS service integration.
 *
 * NOTE: AWS SDK v2 dependencies are temporarily commented out in the POM
 * because the artifacts are not available in the local Maven repository
 * (no network access to Maven Central).
 *
 * When network access is restored, uncomment the AWS SDK dependencies in the
 * parent POM and all service POMs, then uncomment the bean methods below to
 * recreate AWS client beans.
 *
 * These beans are available to all services that import platform-lib.
 */
@Configuration
@EnableConfigurationProperties(AwsProperties.class)
public class AwsConfig {

    /**
     * Placeholder: AWS credentials provider bean.
     * Temporarily disabled because software.amazon.awssdk.auth.credentials
     * classes require the aws-core SDK artifact which is not available locally.
     */

    /**
     * Placeholder: All AWS client beans (S3, DynamoDB, SQS, SNS, Secrets Manager,
     * KMS, CloudWatch, IAM, ElastiCache, Lambda, Athena) are temporarily disabled.
     * Uncomment the bean methods in AwsConfig.java when AWS SDK dependencies
     * are restored in the POM files.
     */

}
