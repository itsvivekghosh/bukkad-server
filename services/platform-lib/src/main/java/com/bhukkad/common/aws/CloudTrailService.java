package com.bhukkad.common.aws;

import org.springframework.stereotype.Service;
import software.amazon.awssdk.services.cloudtrail.CloudTrailClient;
import software.amazon.awssdk.services.cloudtrail.model.*;

import java.util.List;

/**
 * CloudTrail service for audit logging and compliance.
 * <p>
 * Provides operations for creating trails and managing audit logs.
 * Fully testable against Floci emulator locally.
 */
@Service
public class CloudTrailService {

    private final CloudTrailClient cloudTrailClient;
    private final AwsProperties awsProperties;

    public CloudTrailService(CloudTrailClient cloudTrailClient, AwsProperties awsProperties) {
        this.cloudTrailClient = cloudTrailClient;
        this.awsProperties = awsProperties;
    }

    /**
     * Creates a CloudTrail trail for auditing API activity.
     */
    public String createTrail(String trailName, String s3BucketName) {
        return cloudTrailClient.createTrail(CreateTrailRequest.builder()
                .name(trailName)
                .s3BucketName(s3BucketName)
                .build()).trailARN();
    }

    /**
     * Lists all trails.
     */
    public List<TrailInfo> listTrails() {
        return cloudTrailClient.listTrails(ListTrailsRequest.builder().build()).trails();
    }

    /**
     * Starts logging for a trail.
     */
    public void startLogging(String trailName) {
        cloudTrailClient.startLogging(StartLoggingRequest.builder()
                .name(trailName)
                .build());
    }

    /**
     * Stops logging for a trail.
     */
    public void stopLogging(String trailName) {
        cloudTrailClient.stopLogging(StopLoggingRequest.builder()
                .name(trailName)
                .build());
    }
}