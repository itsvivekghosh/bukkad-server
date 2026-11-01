package com.bhukkad.admin.aws;

/**
 * AWS Service implementations - TEMPORARILY DISABLED.
 *
 * AWS SDK v2 dependencies are not available in the local Maven repository
 * because aws-sdk-bom:2.31.5 does not exist in Maven Central and individual
 * AWS SDK modules are not available locally. When network access and correct
 * artifact versions are available, uncomment the AWS SDK dependencies in the
 * POM files and restore the full implementation from git commit 9873efa6.
 */

@org.springframework.context.annotation.Configuration
public class AdminAwsService {
    // All AWS SDK methods temporarily disabled.
    // Restore from git commit 9873efa6 when AWS SDK artifacts are available.
}
