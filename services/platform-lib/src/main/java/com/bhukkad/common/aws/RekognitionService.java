package com.bhukkad.common.aws;

import org.springframework.stereotype.Service;
import software.amazon.awssdk.services.rekognition.RekognitionClient;
import software.amazon.awssdk.services.rekognition.model.*;

import java.util.List;

/**
 * AWS Rekognition service for image and video analysis.
 * <p>
 * Provides operations for analyzing images and videos using Amazon Rekognition.
 * <p>
 * Use cases in Bhukkad:
 * - Restaurant image moderation
 *   <ul>
 *     <li>Fully testable against Floci emulator locally.</li>
 * </ul>
 */
@Service
public class RekognitionService {

    private final RekognitionClient rekognitionClient;

    public RekognitionService(RekognitionClient rekognitionClient) {
        this.rekognitionClient = rekognitionClient;
    }

    /**
     * Detects labels in an image.
     * <p>
     * Example usage:
     * <pre>
     *   DetectLabelsResponse result = rekognitionService.detectLabels("my-bucket", "photo.jpg");
     * </ul>
     */
    public DetectLabelsResponse detectLabels(String bucket, String imageName) {
        return rekognitionClient.detectLabels(DetectLabelsRequest.builder()
                .image(Image.builder()
                        .s3Object(S3Object.builder()
                                .bucket(bucket)
                                .name(imageName)
                                .build())
                        .build())
                .maxLabels(10)
                .build());
    }

    /**
     * Detects faces in an image.
     */
    public DetectFacesResponse detectFaces(String bucket, String imageName) {
        return rekognitionClient.detectFaces(DetectFacesRequest.builder()
                .image(Image.builder()
                        .s3Object(S3Object.builder()
                                .bucket(bucket)
                                .name(imageName)
                                .build())
                        .build())
                .build());
    }

    /**
     * Detects text in an image.
     */
    public DetectTextResponse detectText(String bucket, String imageName) {
        return rekognitionClient.detectText(DetectTextRequest.builder()
                .image(Image.builder()
                        .s3Object(S3Object.builder()
                                .bucket(bucket)
                                .name(imageName)
                                .build())
                        .build())
                .build());
    }

    /**
     * Moderates image content for safety.
     * <p>
     * Example usage:
     * <pre>
     *   DetectModerationLabelsResponse result = rekognitionService.detectModerationLabels("my-bucket", "photo.jpg");
     * </ul>
     */
    public DetectModerationLabelsResponse detectModerationLabels(String bucket, String imageName) {
        return rekognitionClient.detectModerationLabels(DetectModerationLabelsRequest.builder()
                .image(Image.builder()
                        .s3Object(S3Object.builder()
                                .bucket(bucket)
                                .name(imageName)
                                .build())
                        .build())
                .build());
    }
}