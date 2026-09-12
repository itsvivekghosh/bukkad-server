package com.bhukkad.order.domain.service.impl;

import com.bhukkad.common.error.ResourceNotFoundException;
import com.bhukkad.order.api.dto.response.OrderCreateJobResponse;
import com.bhukkad.order.api.dto.response.OrderResponse;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.Duration;
import java.util.UUID;
import com.bhukkad.order.domain.entity.Order;

@Slf4j
@Service
@RequiredArgsConstructor
public class OrderCreateJobService {

    public static final String STATUS_PROCESSING = "PROCESSING";
    public static final String STATUS_COMPLETED = "COMPLETED";
    public static final String STATUS_FAILED = "FAILED";

    private static final String KEY_PREFIX = "order-create-job:";
    private static final String OWNER_KEY_PREFIX = "order-create-job-owner:";
    private static final Duration JOB_TTL = Duration.ofHours(24);

    private final StringRedisTemplate stringRedisTemplate;
    private final ObjectMapper objectMapper;

    public String createJob(Long customerId, String idempotencyKey) {
        String jobId = StringUtils.hasText(idempotencyKey) ? idempotencyKey : UUID.randomUUID().toString();
        save(jobId, processing(jobId));
        saveOwner(jobId, customerId);
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

    /**
     * Returns the job only to the customer who created it (audit LOW-IDOR-5).
     * The job id doubles as the client-supplied idempotency key, so job ids
     * are guessable between customers; the creating customer id is recorded at
     * {@link #createJob} time and re-checked here. A missing owner binding
     * (expired or written by an older deployment) and a mismatched owner both
     * answer the same 404 the unknown-job case produces, so polling cannot be
     * used to probe other customers' jobs. {@code customerId == null} is the
     * privileged (ADMIN) path and skips the match.
     */
    public OrderCreateJobResponse getJob(Long customerId, String jobId) {
        requireOwner(customerId, jobId);
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

    private void requireOwner(Long customerId, String jobId) {
        if (customerId == null) {
            return; // privileged caller (admin override at the controller)
        }
        String owner = stringRedisTemplate.opsForValue().get(OWNER_KEY_PREFIX + jobId);
        if (!StringUtils.hasText(owner) || !owner.equals(String.valueOf(customerId))) {
            throw new ResourceNotFoundException("Order create job not found");
        }
    }

    private void saveOwner(String jobId, Long customerId) {
        stringRedisTemplate.opsForValue().set(
                OWNER_KEY_PREFIX + jobId,
                String.valueOf(customerId),
                JOB_TTL);
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
