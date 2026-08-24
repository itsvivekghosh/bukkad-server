package com.bhukkad.order;

import com.bhukkad.dto.response.OrderCreateJobResponse;
import com.bhukkad.dto.response.OrderResponse;
import com.bhukkad.exception.ResourceNotFoundException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ser.impl.SimpleFilterProvider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.Duration;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class OrderCreateJobServiceTest {

    @Mock
    private StringRedisTemplate stringRedisTemplate;
    @Mock
    private ValueOperations<String, String> valueOps;

    private OrderCreateJobService service;

    private final ObjectMapper objectMapper = new ObjectMapper()
            .setFilterProvider(new SimpleFilterProvider().setFailOnUnknownId(false));

    @BeforeEach
    void setUp() {
        org.mockito.Mockito.lenient().when(stringRedisTemplate.opsForValue()).thenReturn(valueOps);
        service = new OrderCreateJobService(stringRedisTemplate, objectMapper);
    }

    @Test
    void createJob_usesIdempotencyKey() {
        String jobId = service.createJob("customer-123");
        assertEquals("customer-123", jobId);
        verify(valueOps).set(eq("order-create-job:customer-123"), anyString(), eq(Duration.ofHours(24)));
    }

    @Test
    void createJob_generatesJobId_whenBlank() {
        String jobId = service.createJob("");
        assertNotNull(jobId);
        verify(valueOps).set(eq("order-create-job:" + jobId), anyString(), eq(Duration.ofHours(24)));
    }

    @Test
    void markProcessing_savesProcessingStatus() {
        service.markProcessing("job-1");
        verify(valueOps).set(eq("order-create-job:job-1"), anyString(), eq(Duration.ofHours(24)));
    }

    @Test
    void markCompleted_savesCompletedStatus() throws Exception {
        OrderResponse order = new OrderResponse();
        service.markCompleted("job-1", order);
        String payload = valueOpsCaptured();
        OrderCreateJobResponse parsed = objectMapper.readValue(payload, OrderCreateJobResponse.class);
        assertEquals(OrderCreateJobService.STATUS_COMPLETED, parsed.getStatus());
    }

    @Test
    void markFailed_savesFailedStatus() throws Exception {
        service.markFailed("job-1", "payment declined");
        String payload = valueOpsCaptured();
        OrderCreateJobResponse parsed = objectMapper.readValue(payload, OrderCreateJobResponse.class);
        assertEquals(OrderCreateJobService.STATUS_FAILED, parsed.getStatus());
        assertEquals("payment declined", parsed.getMessage());
    }

    @Test
    void getJob_returnsParsedJob() throws Exception {
        OrderCreateJobResponse job = OrderCreateJobResponse.builder()
                .jobId("job-1")
                .status(OrderCreateJobService.STATUS_PROCESSING)
                .build();
        when(valueOps.get("order-create-job:job-1"))
                .thenReturn(objectMapper.writeValueAsString(job));

        OrderCreateJobResponse result = service.getJob("job-1");
        assertEquals("job-1", result.getJobId());
        assertEquals(OrderCreateJobService.STATUS_PROCESSING, result.getStatus());
    }

    @Test
    void getJob_throws_whenNotFound() {
        when(valueOps.get("order-create-job:missing")).thenReturn(null);
        assertThrows(ResourceNotFoundException.class, () -> service.getJob("missing"));
    }

    private String valueOpsCaptured() {
        // Re-run the save through a spy-free approach: verify and return the stored payload.
        org.mockito.ArgumentCaptor<String> captor = org.mockito.ArgumentCaptor.forClass(String.class);
        verify(valueOps).set(eq("order-create-job:job-1"), captor.capture(), eq(Duration.ofHours(24)));
        return captor.getValue();
    }
}