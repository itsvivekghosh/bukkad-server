package com.bhukkad.storage;

import com.bhukkad.exception.BusinessException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.s3.presigner.model.GetObjectPresignRequest;
import software.amazon.awssdk.services.s3.presigner.model.PresignedGetObjectRequest;
import software.amazon.awssdk.services.s3.presigner.model.PresignedPutObjectRequest;
import software.amazon.awssdk.services.s3.presigner.model.PutObjectPresignRequest;

import java.net.URL;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class MenuImageServiceTest {

    @Mock
    private ImageStorageProperties properties;
    @Mock
    private S3Presigner s3Presigner;

    @InjectMocks
    private MenuImageService service;

    @BeforeEach
    void setUp() {
        lenient().when(properties.isEnabled()).thenReturn(true);
        lenient().when(properties.getBucket()).thenReturn("test-bucket");
        lenient().when(properties.getKeyPrefix()).thenReturn("images");
        lenient().when(properties.getUploadUrlExpirySeconds()).thenReturn(900L);
        lenient().when(properties.getDownloadUrlExpirySeconds()).thenReturn(3600L);
        lenient().when(properties.getCloudfront()).thenReturn(new ImageStorageProperties.CloudFront());
    }

    @Test
    void generateImageKey_returnsValidKey() {
        String key = service.generateImageKey(1L, 2L, "image/jpeg");
        assertTrue(key.startsWith("images/1/2/"));
        assertTrue(key.endsWith(".jpg"));
    }

    @Test
    void generateImageKey_throwsOnInvalidType() {
        assertThrows(BusinessException.class,
                () -> service.generateImageKey(1L, 2L, "application/pdf"));
    }

    @Test
    void createUploadUrl_returnsPresignedUrl() throws Exception {
        var presigned = mock(PresignedPutObjectRequest.class);
        when(presigned.url()).thenReturn(new URL("https://s3.test/upload"));
        when(s3Presigner.presignPutObject(any(PutObjectPresignRequest.class)))
                .thenReturn(presigned);

        String url = service.createUploadUrl("images/1/2/test.jpg", "image/jpeg");
        assertEquals("https://s3.test/upload", url);
    }

    @Test
    void createUploadUrl_throwsWhenDisabled() {
        when(properties.isEnabled()).thenReturn(false);
        assertThrows(BusinessException.class,
                () -> service.createUploadUrl("images/key", "image/jpeg"));
    }

    @Test
    void createUploadUrl_throwsWhenImageKeyMissing() {
        assertThrows(BusinessException.class,
                () -> service.createUploadUrl("", "image/jpeg"));
    }

    @Test
    void createUploadUrl_throwsWhenImageKeyInvalid() {
        assertThrows(BusinessException.class,
                () -> service.createUploadUrl("wrong-prefix/key.jpg", "image/jpeg"));
    }

    @Test
    void validateContentType_acceptsAllowedTypes() {
        assertDoesNotThrow(() -> service.validateContentType("image/jpeg"));
        assertDoesNotThrow(() -> service.validateContentType("image/png"));
        assertDoesNotThrow(() -> service.validateContentType("image/webp"));
        assertDoesNotThrow(() -> service.validateContentType("image/gif"));
    }

    @Test
    void validateContentType_rejectsDisallowedTypes() {
        assertThrows(BusinessException.class,
                () -> service.validateContentType("application/pdf"));
        assertThrows(BusinessException.class,
                () -> service.validateContentType("text/plain"));
    }

    @Test
    void validateContentType_rejectsBlankAndNull() {
        assertThrows(BusinessException.class,
                () -> service.validateContentType(""));
        assertThrows(BusinessException.class,
                () -> service.validateContentType(null));
    }

    @Test
    void resolvePublicUrl_returnsUrl() throws Exception {
        var presigned = mock(PresignedGetObjectRequest.class);
        when(presigned.url()).thenReturn(new URL("https://s3.test/images/1/2/test.jpg"));
        when(s3Presigner.presignGetObject(any(GetObjectPresignRequest.class)))
                .thenReturn(presigned);

        String url = service.resolvePublicUrl("images/1/2/test.jpg");
        assertEquals("https://s3.test/images/1/2/test.jpg", url);
    }

    @Test
    void resolvePublicUrl_returnsNullForBlank() {
        assertNull(service.resolvePublicUrl(""));
        assertNull(service.resolvePublicUrl(null));
    }

    @Test
    void resolvePublicUrl_returnsHttpUrlDirectly() {
        String url = service.resolvePublicUrl("https://example.com/image.jpg");
        assertEquals("https://example.com/image.jpg", url);
    }

    @Test
    void resolvePublicUrl_returnsStoredValueWhenDisabled() {
        when(properties.isEnabled()).thenReturn(false);
        String url = service.resolvePublicUrl("images/1/2/test.jpg");
        assertEquals("images/1/2/test.jpg", url);
    }

    @Test
    void resolvePublicUrl_cloudfrontEnabled_servesFromCdn() {
        ImageStorageProperties.CloudFront cloudFront = new ImageStorageProperties.CloudFront();
        cloudFront.setEnabled(true);
        cloudFront.setDomain("d2abc3xyz.cloudfront.net");
        when(properties.getCloudfront()).thenReturn(cloudFront);

        String url = service.resolvePublicUrl("images/1/2/test.jpg");

        assertEquals("https://d2abc3xyz.cloudfront.net/images/1/2/test.jpg", url);
    }

    @Test
    void resolvePublicUrl_cloudfrontNotConfigured_fallsBackToS3Presign() throws Exception {
        ImageStorageProperties.CloudFront cloudFront = new ImageStorageProperties.CloudFront();
        cloudFront.setEnabled(true);
        cloudFront.setDomain(""); // not configured
        when(properties.getCloudfront()).thenReturn(cloudFront);

        URL url = new URL("https://s3.example.com/test-bucket/images/1/2/test.jpg?X-Amz-Signature=abc");
        PresignedGetObjectRequest presigned = mock(PresignedGetObjectRequest.class);
        when(presigned.url()).thenReturn(url);
        when(s3Presigner.presignGetObject(any(GetObjectPresignRequest.class))).thenReturn(presigned);

        String result = service.resolvePublicUrl("images/1/2/test.jpg");

        assertEquals(url.toString(), result);
    }

}
