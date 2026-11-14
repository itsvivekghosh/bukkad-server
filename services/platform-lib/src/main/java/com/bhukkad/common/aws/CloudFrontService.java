package com.bhukkad.common.aws;

import org.springframework.stereotype.Service;
import software.amazon.awssdk.services.cloudfront.CloudFrontClient;
import software.amazon.awssdk.services.cloudfront.model.*;

/**
 * CloudFront service for content delivery network (CDN) management.
 * <p>
 * Provides operations for managing CloudFront distributions for serving
 * static assets (restaurant images, menu items, customer avatars) with
 * low latency globally.
 * <p>
 * Use cases in Bhukkad:
 * - Serve restaurant menu images from edge locations close to customers
 * - Cache static assets (CSS, JS, images) for faster page loads
 * - Serve customer profile images and delivery agent photos
 * <p>
 * Fully testable against Floci emulator locally.
 */
@Service
public class CloudFrontService {

    private final CloudFrontClient cloudFrontClient;
    private final AwsProperties awsProperties;

    public CloudFrontService(CloudFrontClient cloudFrontClient, AwsProperties awsProperties) {
        this.cloudFrontClient = cloudFrontClient;
        this.awsProperties = awsProperties;
    }

    /**
     * Lists all CloudFront distributions.
     */
    public ListDistributionsResponse listDistributions() {
        return cloudFrontClient.listDistributions(ListDistributionsRequest.builder().build());
    }

    /**
     * Gets a distribution by ID.
     */
    public GetDistributionResponse getDistribution(String distributionId) {
        return cloudFrontClient.getDistribution(GetDistributionRequest.builder()
                .id(distributionId)
                .build());
    }
}