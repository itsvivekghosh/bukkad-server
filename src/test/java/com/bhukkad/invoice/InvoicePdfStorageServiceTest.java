package com.bhukkad.invoice;

import com.bhukkad.storage.ImageStorageProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.PutObjectResponse;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.s3.presigner.model.GetObjectPresignRequest;
import software.amazon.awssdk.services.s3.presigner.model.PresignedGetObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;

import java.net.URL;
import java.time.Duration;
import java.time.LocalDate;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class InvoicePdfStorageServiceTest {

    @Mock
    private ImageStorageProperties properties;
    @Mock
    private S3Client s3Client;
    @Mock
    private S3Presigner s3Presigner;

    private InvoicePdfStorageService service;

    @BeforeEach
    void setUp() {
        service = new InvoicePdfStorageService(properties, s3Client, s3Presigner);
        lenient().when(properties.getBucket()).thenReturn("test-bucket");
        lenient().when(properties.getDownloadUrlExpirySeconds()).thenReturn(3600L);
    }

    @Test
    void isEnabled_returnsFalse_whenPropertiesDisabled() {
        when(properties.isEnabled()).thenReturn(false);

        assertFalse(service.isEnabled());
    }

    @Test
    void isEnabled_returnsFalse_whenS3ClientNull() {
        when(properties.isEnabled()).thenReturn(true);

        InvoicePdfStorageService serviceNoClient = new InvoicePdfStorageService(properties, null, s3Presigner);
        assertFalse(serviceNoClient.isEnabled());
    }

    @Test
    void isEnabled_returnsTrue_whenBothEnabled() {
        when(properties.isEnabled()).thenReturn(true);

        assertTrue(service.isEnabled());
    }

    @Test
    void buildKey_returnsCorrectFormat() {
        String invoiceNumber = "INV-12345";
        String key = service.buildKey(invoiceNumber);

        assertTrue(key.startsWith("invoices/"));
        assertTrue(key.endsWith(".pdf"));
        assertTrue(key.contains(invoiceNumber));

        // Check date format
        LocalDate today = LocalDate.now();
        assertTrue(key.contains("/" + today.getYear() + "/"));
        assertTrue(key.contains(String.format("/%02d/", today.getMonthValue())));
    }

    @Test
    void store_returnsNull_whenDisabled() {
        when(properties.isEnabled()).thenReturn(false);

        String result = service.store("INV-123", "test".getBytes());

        assertNull(result);
        verifyNoInteractions(s3Client);
    }

    @Test
    void store_returnsNull_whenPdfNull() {
        when(properties.isEnabled()).thenReturn(true);

        String result = service.store("INV-123", null);

        assertNull(result);
    }

    @Test
    void store_returnsNull_whenPdfEmpty() {
        when(properties.isEnabled()).thenReturn(true);

        String result = service.store("INV-123", new byte[0]);

        assertNull(result);
    }

    @Test
    void store_returnsKey_onSuccess() {
        lenient().when(properties.isEnabled()).thenReturn(true);
        lenient().when(s3Client.putObject(any(PutObjectRequest.class), any(RequestBody.class)))
                .thenReturn(PutObjectResponse.builder().build());

        String result = service.store("INV-12345", "test content".getBytes());

        assertNotNull(result);
        assertTrue(result.startsWith("invoices/"));
        assertTrue(result.endsWith("INV-12345.pdf"));
        verify(s3Client).putObject(any(PutObjectRequest.class), any(RequestBody.class));
    }

    @Test
    void store_returnsNull_onS3Exception() {
        lenient().when(properties.isEnabled()).thenReturn(true);
        lenient().when(s3Client.putObject(any(PutObjectRequest.class), any(RequestBody.class)))
                .thenThrow(new RuntimeException("S3 error"));

        String result = service.store("INV-123", "test".getBytes());

        assertNull(result);
    }

    @Test
    void presignedUrl_returnsNull_whenKeyBlank() {
        // No stubs needed - method returns early for blank keys
        assertNull(service.presignedUrl(""));
        assertNull(service.presignedUrl(null));
        assertNull(service.presignedUrl("  "));
    }

    @Test
    void presignedUrl_returnsNull_whenPropertiesDisabled() {
        lenient().when(properties.isEnabled()).thenReturn(false);

        assertNull(service.presignedUrl("invoices/2026/08/INV-123.pdf"));
    }

    @Test
    void presignedUrl_returnsNull_whenPresignerNull() {
        lenient().when(properties.isEnabled()).thenReturn(true);

        InvoicePdfStorageService serviceNoPresigner = new InvoicePdfStorageService(properties, s3Client, null);
        assertNull(serviceNoPresigner.presignedUrl("invoices/2026/08/INV-123.pdf"));
    }

    @Test
    void presignedUrl_returnsUrl_onSuccess() throws Exception {
        when(properties.isEnabled()).thenReturn(true);
        when(properties.getBucket()).thenReturn("test-bucket");
        when(properties.getDownloadUrlExpirySeconds()).thenReturn(3600L);

        var presigned = mock(PresignedGetObjectRequest.class);
        when(presigned.url()).thenReturn(new URL("https://s3.test/invoices/INV-123.pdf"));
        when(s3Presigner.presignGetObject(any(GetObjectPresignRequest.class))).thenReturn(presigned);

        String url = service.presignedUrl("invoices/2026/08/INV-123.pdf");

        assertEquals("https://s3.test/invoices/INV-123.pdf", url);
        verify(s3Presigner).presignGetObject(any(GetObjectPresignRequest.class));
    }

    @Test
    void presignedUrl_returnsNull_onException() {
        when(properties.isEnabled()).thenReturn(true);
        when(properties.getBucket()).thenReturn("test-bucket");
        when(properties.getDownloadUrlExpirySeconds()).thenReturn(3600L);
        when(s3Presigner.presignGetObject(any(GetObjectPresignRequest.class)))
                .thenThrow(new RuntimeException("Presign failed"));

        String url = service.presignedUrl("invoices/2026/08/INV-123.pdf");

        assertNull(url);
    }
}