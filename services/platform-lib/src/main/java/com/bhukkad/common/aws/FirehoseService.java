package com.bhukkad.common.aws;

import org.springframework.stereotype.Service;
import software.amazon.awssdk.core.SdkBytes;
import software.amazon.awssdk.services.firehose.FirehoseClient;
import software.amazon.awssdk.services.firehose.model.*;

/**
 * Firehose service for data streaming delivery.
 * <p>
 * Provides operations for creating Kinesis Data Firehose delivery streams
 * for loading streaming data into S3, Redshift, Elasticsearch, etc.
 * Fully testable against Floci emulator locally.
 */
@Service
public class FirehoseService {

    private final FirehoseClient firehoseClient;
    private final AwsProperties awsProperties;

    public FirehoseService(FirehoseClient firehoseClient, AwsProperties awsProperties) {
        this.firehoseClient = firehoseClient;
        this.awsProperties = awsProperties;
    }

    /**
     * Creates a Firehose delivery stream delivering to S3.
     */
    public CreateDeliveryStreamResponse createDeliveryStream(
            String streamName, String s3BucketArn, String roleArn) {
        return firehoseClient.createDeliveryStream(CreateDeliveryStreamRequest.builder()
                .deliveryStreamName(streamName)
                .deliveryStreamType(DeliveryStreamType.DIRECT_PUT)
                .s3DestinationConfiguration(S3DestinationConfiguration.builder()
                        .roleARN(roleArn)
                        .bucketARN(s3BucketArn)
                        .build())
                .build());
    }

    /**
     * Describes a delivery stream configuration.
     */
    public DescribeDeliveryStreamResponse describeDeliveryStream(String streamName) {
        return firehoseClient.describeDeliveryStream(DescribeDeliveryStreamRequest.builder()
                .deliveryStreamName(streamName)
                .build());
    }

    /**
     * Lists all delivery streams.
     */
    public ListDeliveryStreamsResponse listDeliveryStreams() {
        return firehoseClient.listDeliveryStreams(ListDeliveryStreamsRequest.builder().build());
    }

    /**
     * Deletes a delivery stream.
     */
    public DeleteDeliveryStreamResponse deleteDeliveryStream(String streamName) {
        return firehoseClient.deleteDeliveryStream(DeleteDeliveryStreamRequest.builder()
                .deliveryStreamName(streamName)
                .build());
    }

    /**
     * Puts a record into a delivery stream.
     */
    public PutRecordResponse putRecord(String streamName, String data) {
        software.amazon.awssdk.services.firehose.model.Record record =
                software.amazon.awssdk.services.firehose.model.Record.builder()
                        .data(SdkBytes.fromUtf8String(data))
                        .build();
        return firehoseClient.putRecord(PutRecordRequest.builder()
                .deliveryStreamName(streamName)
                .record(record)
                .build());
    }
}