package com.bhukkad.realtime.config;

import com.bhukkad.realtime.exception.SseCapacityExceededException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.Map;

@Slf4j
@RestControllerAdvice
public class RealtimeExceptionHandler {

    @ExceptionHandler(SseCapacityExceededException.class)
    public ResponseEntity<Map<String, String>> handleSseCapacityExceeded(SseCapacityExceededException ex) {
        log.warn("SSE capacity exceeded: {}", ex.getMessage());
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .body(Map.of("error", "SSE capacity exceeded", "message", ex.getMessage()));
    }

    @ExceptionHandler(IllegalStateException.class)
    public ResponseEntity<Map<String, String>> handleIllegalState(IllegalStateException ex) {
        log.error("Illegal state: {}", ex.getMessage());
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(Map.of("error", "Internal error", "message", ex.getMessage()));
    }
}
