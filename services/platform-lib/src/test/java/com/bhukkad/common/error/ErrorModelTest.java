package com.bhukkad.common.error;

import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class ErrorModelTest {

    @Test
    void businessException_carriesCodeAndMessage() {
        BusinessException ex = new BusinessException("INSUFFICIENT_BALANCE", "balance too low");
        assertThat(ex.getCode()).isEqualTo("INSUFFICIENT_BALANCE");
        assertThat(ex.getMessage()).isEqualTo("balance too low");
        assertThat(ex).isInstanceOf(RuntimeException.class);
    }

    @Test
    void duplicateRequestException_hasDuplicateCode() {
        DuplicateRequestException ex = new DuplicateRequestException("dup");
        assertThat(ex.getCode()).isEqualTo("DUPLICATE_REQUEST");
    }

    @Test
    void resourceNotFoundException_hasNotFoundCode() {
        ResourceNotFoundException ex = new ResourceNotFoundException("missing");
        assertThat(ex.getCode()).isEqualTo("NOT_FOUND");
    }

    @Test
    void apiError_buildsEnvelope() {
        ApiError error = ApiError.of(409, "DUPLICATE_REQUEST", "dup", "trace-1");
        assertThat(error.status()).isEqualTo(409);
        assertThat(error.code()).isEqualTo("DUPLICATE_REQUEST");
        assertThat(error.message()).isEqualTo("dup");
        assertThat(error.traceId()).isEqualTo("trace-1");
        assertThat(error.timestamp()).isBeforeOrEqualTo(Instant.now());
    }
}
