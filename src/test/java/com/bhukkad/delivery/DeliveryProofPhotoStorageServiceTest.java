package com.bhukkad.delivery;

import com.bhukkad.exception.BusinessException;
import com.bhukkad.storage.ImageStorageProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.s3.presigner.model.GetObjectPresignRequest;
import software.amazon.awssdk.services.s3.presigner.model.PresignedGetObjectRequest;
import software.amazon.awssdk.services.s3.presigner.model.PresignedPutObjectRequest;
import software.amazon.awssdk.services.s3.presigner.model.PutObjectPresignRequest;

import java.net.URL;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DeliveryProofPhotoStorageServiceTest {

    private static final String KEY = "delivery-proofs/2026/08/42/3f1c9b2a-1234-4c56-8d78-90abcdef1234.jpg";

    private final ImageStorageProperties properties = new ImageStorageProperties();

    @Mock
    private S3Presigner s3Presigner;

    private DeliveryProofPhotoStorageService service;

    @BeforeEach
    void setUp() {
        properties.setEnabled(true);
        properties.setBucket("bhukkad-proofs");
        service = new DeliveryProofPhotoStorageService(properties, s3Presigner);
    }

    @Test
    void isEnabled_enabledAndPresignerPresent_returnsTrue() {
        assertTrue(service.isEnabled());
    }

    @Test
    void isEnabled_disabled_returnsFalse() {
        properties.setEnabled(false);
        assertFalse(service.isEnabled());
    }

    @Test
    void isEnabled_presignerMissing_returnsFalse() {
        service = new DeliveryProofPhotoStorageService(properties, null);
        assertFalse(service.isEnabled());
    }

    @Test
    void buildKey_jpeg_appendsJpgExtension() {
        String key = service.buildKey(42L, "image/jpeg");

        assertTrue(key.startsWith("delivery-proofs/"));
        assertTrue(key.matches("delivery-proofs/\\d{4}/\\d{2}/42/[0-9a-f-]+\\.jpg"));
    }

    @Test
    void buildKey_png_appendsPngExtension() {
        String key = service.buildKey(42L, "image/png");

        assertTrue(key.matches("delivery-proofs/\\d{4}/\\d{2}/42/[0-9a-f-]+\\.png"));
    }

    @Test
    void buildKey_webp_appendsWebpExtension() {
        String key = service.buildKey(42L, "image/webp");

        assertTrue(key.matches("delivery-proofs/\\d{4}/\\d{2}/42/[0-9a-f-]+\\.webp"));
    }

    @Test
    void buildKey_uppercaseContentType_isAccepted() {
        String key = service.buildKey(42L, "IMAGE/PNG");

        assertTrue(key.endsWith(".png"));
    }

    @Test
    void buildKey_nullContentType_throws() {
        BusinessException ex = assertThrows(BusinessException.class, () -> service.buildKey(42L, null));

        assertEquals("Unsupported delivery proof photo content type", ex.getMessage());
    }

    @Test
    void buildKey_blankContentType_throws() {
        assertThrows(BusinessException.class, () -> service.buildKey(42L, " "));
    }

    @Test
    void buildKey_unsupportedContentType_throws() {
        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.buildKey(42L, "image/svg+xml"));

        assertEquals("Unsupported delivery proof photo content type", ex.getMessage());
    }

    @Test
    void createUploadUrl_success_returnsPresignedUrl() throws Exception {
        PresignedPutObjectRequest presigned = mock(PresignedPutObjectRequest.class);
        when(s3Presigner.presignPutObject(any(PutObjectPresignRequest.class))).thenReturn(presigned);
        when(presigned.url()).thenReturn(new URL("https://s3.example.com/upload"));

        String url = service.createUploadUrl(KEY, "image/jpeg");

        assertEquals("https://s3.example.com/upload", url);
        verify(s3Presigner).presignPutObject(any(PutObjectPresignRequest.class));
    }

    @Test
    void createUploadUrl_storageDisabled_throws() {
        properties.setEnabled(false);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.createUploadUrl(KEY, "image/jpeg"));

        assertEquals("Delivery proof photo uploads are not enabled", ex.getMessage());
    }

    @Test
    void createUploadUrl_blankKey_throws() {
        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.createUploadUrl(null, "image/jpeg"));

        assertEquals("Delivery proof photo key is required", ex.getMessage());
    }

    @Test
    void createUploadUrl_invalidKeyPrefix_throws() {
        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.createUploadUrl("menu-items/1/2.jpg", "image/jpeg"));

        assertEquals("Invalid delivery proof photo key", ex.getMessage());
    }

    @Test
    void createUploadUrl_keyWithoutPrefix_throws() {
        assertThrows(BusinessException.class,
                () -> service.createUploadUrl("delivery-proofs", "image/jpeg"));
    }

    @Test
    void createUploadUrl_unsupportedContentType_throws() {
        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.createUploadUrl(KEY, "text/html"));

        assertEquals("Unsupported delivery proof photo content type", ex.getMessage());
    }

    @Test
    void createUploadUrl_presignerMissing_throws() {
        service = new DeliveryProofPhotoStorageService(properties, null);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.createUploadUrl(KEY, "image/jpeg"));

        assertEquals("S3 presigner is not configured", ex.getMessage());
    }

    @Test
    void presignedUrl_success_returnsUrl() throws Exception {
        PresignedGetObjectRequest presigned = mock(PresignedGetObjectRequest.class);
        when(s3Presigner.presignGetObject(any(GetObjectPresignRequest.class))).thenReturn(presigned);
        when(presigned.url()).thenReturn(new URL("https://s3.example.com/download"));

        String url = service.presignedUrl(KEY);

        assertEquals("https://s3.example.com/download", url);
        verify(s3Presigner).presignGetObject(any(GetObjectPresignRequest.class));
    }

    @Test
    void presignedUrl_nullKey_returnsNull() {
        assertNull(service.presignedUrl(null));
    }

    @Test
    void presignedUrl_blankKey_returnsNull() {
        assertNull(service.presignedUrl("   "));
    }

    @Test
    void presignedUrl_storageDisabled_returnsNull() {
        properties.setEnabled(false);

        assertNull(service.presignedUrl(KEY));
    }

    @Test
    void presignedUrl_presignerMissing_returnsNull() {
        service = new DeliveryProofPhotoStorageService(properties, null);

        assertNull(service.presignedUrl(KEY));
    }

    @Test
    void presignedUrl_presignerThrows_returnsNull() {
        when(s3Presigner.presignGetObject(any(GetObjectPresignRequest.class)))
                .thenThrow(new RuntimeException("s3 down"));

        assertNull(service.presignedUrl(KEY));
    }

    @Test
    void validateKey_validKey_passes() {
        service.validateKey(KEY);
    }

    @Test
    void validateKey_nullKey_throws() {
        BusinessException ex = assertThrows(BusinessException.class, () -> service.validateKey(null));

        assertEquals("Delivery proof photo key is required", ex.getMessage());
    }

    @Test
    void validateKey_blankKey_throws() {
        assertThrows(BusinessException.class, () -> service.validateKey(""));
    }

    @Test
    void validateKey_wrongPrefix_throws() {
        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.validateKey("menu-items/1/2.jpg"));

        assertEquals("Invalid delivery proof photo key", ex.getMessage());
    }

    @Test
    void validateContentType_validTypes_pass() {
        service.validateContentType("image/jpeg");
        service.validateContentType("image/png");
        service.validateContentType("image/webp");
        service.validateContentType("IMAGE/JPEG");
    }

    @Test
    void validateContentType_null_throws() {
        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.validateContentType(null));

        assertEquals("Unsupported delivery proof photo content type", ex.getMessage());
    }

    @Test
    void validateContentType_unsupported_throws() {
        assertThrows(BusinessException.class, () -> service.validateContentType("application/pdf"));
    }
}
