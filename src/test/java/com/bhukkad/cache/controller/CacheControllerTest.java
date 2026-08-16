package com.bhukkad.cache.controller;

import com.bhukkad.cache.RedisCacheService;
import com.bhukkad.dto.response.ApiResponse;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class CacheControllerTest {

    @Mock
    private RedisCacheService cacheService;

    @InjectMocks
    private CacheController cacheController;

    @Test
    void getCacheStats_returnsStats() {
        Map<String, Object> stats = Map.of("totalKeys", 3);
        when(cacheService.getCacheStats()).thenReturn(stats);

        ResponseEntity<ApiResponse<Map<String, Object>>> response = cacheController.getCacheStats();

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals("Cache statistics", response.getBody().getMessage());
        assertEquals(stats, response.getBody().getData());
    }

    @Test
    void clearAllCaches_returnsOk() {
        ResponseEntity<ApiResponse<String>> response = cacheController.clearAllCaches();

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals("All caches cleared", response.getBody().getMessage());
        assertEquals("OK", response.getBody().getData());
        verify(cacheService).clearAll();
    }

    @Test
    void clearCacheByPattern_returnsOk() {
        ResponseEntity<ApiResponse<String>> response = cacheController.clearCacheByPattern("menu");

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals("Cache cleared for pattern: menu", response.getBody().getMessage());
        assertEquals("OK", response.getBody().getData());
        verify(cacheService).deletePattern("menu");
    }

    @Test
    void cacheHealth_returnsUpWhenValuePresent() {
        when(cacheService.get("health-check", String.class)).thenReturn(Optional.of("ok"));
        when(cacheService.getCacheStats()).thenReturn(Map.of("totalKeys", 1));

        ResponseEntity<ApiResponse<Map<String, Object>>> response = cacheController.cacheHealth();

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals("UP", response.getBody().getData().get("status"));
        verify(cacheService).set("health-check", "ok", 10);
        verify(cacheService).delete("health-check");
    }

    @Test
    void cacheHealth_returnsDownWhenValueMissing() {
        when(cacheService.get("health-check", String.class)).thenReturn(Optional.empty());
        when(cacheService.getCacheStats()).thenReturn(Map.of("totalKeys", 0));

        ResponseEntity<ApiResponse<Map<String, Object>>> response = cacheController.cacheHealth();

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals("DOWN", response.getBody().getData().get("status"));
        verify(cacheService).delete("health-check");
    }

    @Test
    void cacheHealth_returnsDownWhenSetThrows() {
        doThrow(new RuntimeException("redis unavailable")).when(cacheService).set("health-check", "ok", 10);

        ResponseEntity<ApiResponse<Map<String, Object>>> response = cacheController.cacheHealth();

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals("DOWN", response.getBody().getData().get("status"));
        assertEquals("redis unavailable", response.getBody().getData().get("error"));
        verify(cacheService, never()).delete("health-check");
    }
}
