package com.bhukkad.order;

import com.bhukkad.dto.response.OrderCreateJobResponse;
import com.bhukkad.dto.response.OrderResponse;
import com.bhukkad.exception.ResourceNotFoundException;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.Duration;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class OrderCreateJobService {

    public static final String STATUS_PROCESSING = "PROCESSING";
    public static final String STATUS_COMPLETED = "COMPLETED";
    public static final String STATUS_FAILED = "FAILED";

    private static final String KEY_PREFIX = "order-create-job:";
    private static final Duration JOB_TTL = Duration.ofHours(24);

    private final StringRedisTemplate stringRedisTemplate;
    private final ObjectMapper objectMapper;

    public String createJob(String idempotencyKey) {
        String jobId = StringUtils.hasText(idempotencyKey) ? idempotencyKey : UUID.randomUUID().toString();
        save(jobId, processing(jobId));
        return jobId;
    }

    public void markProcessing(String jobId) {
        save(jobId, processing(jobId));
    }

    public void markCompleted(String jobId, OrderResponse order) {
        save(jobId, OrderCreateJobResponse.builder()
                .jobId(jobId)
                .status(STATUS_COMPLETED)
                .order(order)
                .pollUrl(pollUrl(jobId))
                .build());
    }

    public void markFailed(String jobId, String message) {
        save(jobId, OrderCreateJobResponse.builder()
                .jobId(jobId)
                .status(STATUS_FAILED)
                .message(message)
                .pollUrl(pollUrl(jobId))
                .build());
    }

    public OrderCreateJobResponse getJob(String jobId) {
        String payload = stringRedisTemplate.opsForValue().get(KEY_PREFIX + jobId);
        if (!StringUtils.hasText(payload)) {
            throw new ResourceNotFoundException("Order create job not found");
        }
        try {
            return objectMapper.readValue(payload, OrderCreateJobResponse.class);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to read order create job", e);
        }
    }

    private OrderCreateJobResponse processing(String jobId) {
        return OrderCreateJobResponse.builder()
                .jobId(jobId)
                .status(STATUS_PROCESSING)
                .pollUrl(pollUrl(jobId))
                .build();
    }

    private void save(String jobId, OrderCreateJobResponse job) {
        try {
            stringRedisTemplate.opsForValue().set(
                    KEY_PREFIX + jobId,
                    objectMapper.writeValueAsString(job),
                    JOB_TTL);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to persist order create job", e);
        }
    }

    private static String pollUrl(String jobId) {
        return "/api/orders/customer/create/jobs/" + jobId;
    }
}
