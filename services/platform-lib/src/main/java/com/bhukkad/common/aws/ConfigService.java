package com.bhukkad.common.aws;

import org.springframework.stereotype.Service;
import software.amazon.awssdk.services.config.ConfigClient;
import software.amazon.awssdk.services.config.model.*;

/**
 * Config service for resource configuration management.
 * <p>
 * Provides operations for managing AWS Config configuration recorders,
 * delivery channels, and config rules for compliance monitoring.
 * Fully testable against Floci emulator locally.
 */
@Service
public class ConfigService {

    private final ConfigClient configClient;
    private final AwsProperties awsProperties;

    public ConfigService(ConfigClient configClient, AwsProperties awsProperties) {
        this.configClient = configClient;
        this.awsProperties = awsProperties;
    }

    /**
     * Creates a Config delivery channel for storing configuration snapshots.
     */
    public PutDeliveryChannelResponse createDeliveryChannel(
            String name, String s3BucketName) {
        return configClient.putDeliveryChannel(PutDeliveryChannelRequest.builder()
                .deliveryChannel(DeliveryChannel.builder()
                        .name(name)
                        .s3BucketName(s3BucketName)
                        .build())
                .build());
    }

    /**
     * Creates a Config recorder for recording configuration changes.
     */
    public PutConfigurationRecorderResponse createConfigurationRecorder(
            String recorderName, String roleArn) {
        return configClient.putConfigurationRecorder(PutConfigurationRecorderRequest.builder()
                .configurationRecorder(ConfigurationRecorder.builder()
                        .name(recorderName)
                        .roleARN(roleArn)
                        .build())
                .build());
    }

    /**
     * Starts recording configuration changes.
     */
    public StartConfigurationRecorderResponse startConfigurationRecorder(String recorderName) {
        return configClient.startConfigurationRecorder(StartConfigurationRecorderRequest.builder()
                .configurationRecorderName(recorderName)
                .build());
    }

    /**
     * Stops recording configuration changes.
     */
    public StopConfigurationRecorderResponse stopConfigurationRecorder(String recorderName) {
        return configClient.stopConfigurationRecorder(StopConfigurationRecorderRequest.builder()
                .configurationRecorderName(recorderName)
                .build());
    }

    /**
     * Lists all configuration recorders.
     */
    public DescribeConfigurationRecordersResponse describeConfigurationRecorders() {
        return configClient.describeConfigurationRecorders(DescribeConfigurationRecordersRequest.builder().build());
    }

    /**
     * Lists all delivery channels.
     */
    public DescribeDeliveryChannelsResponse describeDeliveryChannels() {
        return configClient.describeDeliveryChannels(DescribeDeliveryChannelsRequest.builder().build());
    }
}