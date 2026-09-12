package com.bhukkad.order.domain.service.impl;

import com.bhukkad.common.error.ResourceNotFoundException;
import com.bhukkad.order.api.OrderCreateJobResponse;
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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Audit LOW-IDOR-5: the create-job poll returns the created order, and job
 * ids are client-supplied idempotency keys — so the poll must be bound to the
 * customer recorded at create time. Unknown and foreign jobs must be
 * indistinguishable (404) so polling cannot probe other customers' jobs.
 */
@ExtendWith(MockitoExtension.class)
class OrderCreateJobOwnershipTest {

    private static final String OWNER_PREFIX = "order-create-job-owner:";

    @Mock
    private StringRedisTemplate stringRedisTemplate;
    @Mock
    private ValueOperations<String, String> valueOps;

    private OrderCreateJobService service;

    private final ObjectMapper objectMapper = new ObjectMapper()
            .setFilterProvider(new SimpleFilterProvider().setFailOnUnknownId(false));

    @BeforeEach
    void setUp() {
        lenient().when(stringRedisTemplate.opsForValue()).thenReturn(valueOps);
        service = new OrderCreateJobService(stringRedisTemplate, objectMapper);
    }

    @Test
    void createJob_persistsOwnerBinding() {
        String jobId = service.createJob(7L, "idem-1");

        assertThat(jobId).isEqualTo("idem-1");
        verify(valueOps).set(eq(OWNER_PREFIX + "idem-1"), eq("7"), eq(Duration.ofHours(24)));
    }

    @Test
    void getJob_owner_returnsJob() throws Exception {
        OrderCreateJobResponse job = OrderCreateJobResponse.builder()
                .jobId("job-1")
                .status(OrderCreateJobService.STATUS_PROCESSING)
                .build();
        when(valueOps.get(OWNER_PREFIX + "job-1")).thenReturn("7");
        when(valueOps.get("order-create-job:job-1"))
                .thenReturn(objectMapper.writeValueAsString(job));

        OrderCreateJobResponse result = service.getJob(7L, "job-1");

        assertThat(result.getJobId()).isEqualTo("job-1");
    }

    @Test
    void getJob_differentCustomer_answers404NotForbidden() {
        // Indistinguishable from an unknown job: no existence oracle.
        when(valueOps.get(OWNER_PREFIX + "job-1")).thenReturn("7");

        assertThatThrownBy(() -> service.getJob(8L, "job-1"))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessageContaining("not found");
    }

    @Test
    void getJob_missingOwnerBinding_answers404() {
        // Legacy row written before the binding existed.
        when(valueOps.get(OWNER_PREFIX + "job-1")).thenReturn(null);

        assertThatThrownBy(() -> service.getJob(7L, "job-1"))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void getJob_unknownJob_answers404() {
        when(valueOps.get(OWNER_PREFIX + "missing")).thenReturn("7");
        when(valueOps.get("order-create-job:missing")).thenReturn(null);

        assertThatThrownBy(() -> service.getJob(7L, "missing"))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void getJob_nullCustomerId_privilegedBypass() throws Exception {
        OrderCreateJobResponse job = OrderCreateJobResponse.builder()
                .jobId("job-1")
                .status(OrderCreateJobService.STATUS_PROCESSING)
                .build();
        when(valueOps.get("order-create-job:job-1"))
                .thenReturn(objectMapper.writeValueAsString(job));

        OrderCreateJobResponse result = service.getJob(null, "job-1");

        assertThat(result.getJobId()).isEqualTo("job-1");
        // The owner binding is never consulted for the privileged path.
        verify(valueOps, org.mockito.Mockito.never()).get(org.mockito.ArgumentMatchers.startsWith(OWNER_PREFIX));
    }
}
