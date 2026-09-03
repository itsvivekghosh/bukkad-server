package com.bhukkad.storage;

import com.bhukkad.common.error.BusinessException;
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
import static org.mockito.Mockito.verify;
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

    // ===== Batch B: presign-path + extension branches =====

    @Test
    void resolvePublicUrl_cloudfrontConfigured_servesFromCdnWithoutPresign() {
        ImageStorageProperties.CloudFront cloudFront = new ImageStorageProperties.CloudFront();
        cloudFront.setEnabled(true);
        cloudFront.setDomain("cdn.bhukkad.dev");
        when(properties.getCloudfront()).thenReturn(cloudFront);

        String result = service.resolvePublicUrl("images/1/2/test.jpg");

        assertEquals("https://cdn.bhukkad.dev/images/1/2/test.jpg", result);
        // CDN path must not touch the presigner (no Redis/presign cost on hot path)
        org.mockito.Mockito.verifyNoInteractions(s3Presigner);
    }

    @Test
    void resolvePublicUrl_storageDisabled_returnsStoredValueUnchanged() {
        when(properties.isEnabled()).thenReturn(false);

        String result = service.resolvePublicUrl("images/1/2/test.jpg");

        assertEquals("images/1/2/test.jpg", result);
    }

    @Test
    void resolvePublicUrl_fullHttpUrl_passesThroughWithoutPresign() {
        String result = service.resolvePublicUrl("https://external.example.com/logo.png");

        assertEquals("https://external.example.com/logo.png", result);
        org.mockito.Mockito.verifyNoInteractions(s3Presigner);
    }

    @Test
    void resolvePublicUrl_nullPresigner_returnsStoredValue() {
        MenuImageService noPresigner = new MenuImageService(properties, null, null);

        String result = noPresigner.resolvePublicUrl("images/1/2/test.jpg");

        assertEquals("images/1/2/test.jpg", result);
    }

    @Test
    void createUploadUrl_nullPresigner_throwsBusinessException() {
        MenuImageService noPresigner = new MenuImageService(properties, null, null);

        assertThrows(BusinessException.class,
                () -> noPresigner.createUploadUrl("images/1/2/a.jpg", "image/jpeg"));
    }

    @Test
    void generateImageKey_extensionPerContentType() {
        assertTrue(service.generateImageKey(1L, 2L, "image/png").endsWith(".png"));
        assertTrue(service.generateImageKey(1L, 2L, "image/webp").endsWith(".webp"));
        assertTrue(service.generateImageKey(1L, 2L, "image/gif").endsWith(".gif"));
        assertTrue(service.generateImageKey(1L, 2L, "image/jpeg").endsWith(".jpg"));
    }

    @Test
    void resolvePublicUrl_withRedisCache_cachesPresignedUrl() throws Exception {
        // Batch B: presign URLs are cached in Redis (TTL = expiry - 100s) to avoid
        // 100k presign ops/s at high QPS. The supplier runs on cache miss and the
        // presigned URL is stored under menu-image:url:<key>.
        com.bhukkad.common.cache.RedisCacheService redis = org.mockito.Mockito.mock(com.bhukkad.common.cache.RedisCacheService.class);
        MenuImageService cachedService = new MenuImageService(properties, s3Presigner, redis);

        URL url = new URL("https://s3.example.com/test-bucket/images/1/2/cached.jpg?sig=1");
        PresignedGetObjectRequest presigned = mock(PresignedGetObjectRequest.class);
        when(presigned.url()).thenReturn(url);
        when(s3Presigner.presignGetObject(any(GetObjectPresignRequest.class))).thenReturn(presigned);
        // Delegate to the supplier so the miss path (and its presign call) is exercised
        when(redis.getOrCompute(org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.eq(String.class),
                org.mockito.ArgumentMatchers.eq(3500L),
                org.mockito.ArgumentMatchers.any())).thenAnswer(inv ->
                        ((java.util.function.Supplier<String>) inv.getArgument(3)).get());

        String result = cachedService.resolvePublicUrl("images/1/2/cached.jpg");

        assertEquals(url.toString(), result);
        verify(redis).getOrCompute(org.mockito.ArgumentMatchers.eq("menu-image:url:images/1/2/cached.jpg"),
                org.mockito.ArgumentMatchers.eq(String.class),
                org.mockito.ArgumentMatchers.eq(3500L),
                org.mockito.ArgumentMatchers.any());
    }

}
