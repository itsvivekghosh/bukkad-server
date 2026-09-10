package com.bhukkad.growth.api;

import com.bhukkad.common.error.ApiError;
import com.bhukkad.common.tracing.TraceContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * Growth-specific error mappings on top of the platform handler: the daily
 * credit cap is a business-rule rejection (422), not a 400/500.
 */
@RestControllerAdvice
public class GrowthExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GrowthExceptionHandler.class);

    @ExceptionHandler(LoyaltyDailyCapExceededException.class)
    public ResponseEntity<ApiError> dailyCap(LoyaltyDailyCapExceededException ex) {
        log.warn("Loyalty credit rejected by daily cap: {}", ex.getMessage());
        return ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY)
                .body(ApiError.of(422, "DAILY_CREDIT_CAP_EXCEEDED", ex.getMessage(),
                        TraceContext.currentTraceId()));
    }
}
