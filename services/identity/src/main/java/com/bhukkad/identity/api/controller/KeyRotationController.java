package com.bhukkad.identity.api.controller;

import com.bhukkad.identity.config.RsaSigningKeys;

import com.bhukkad.common.security.PlatformJwtProperties;
import com.bhukkad.common.security.PlatformJwtValidator;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/admin/jwt")
@RequiredArgsConstructor
public class KeyRotationController {

    private final RsaSigningKeys rsaSigningKeys;
    private final PlatformJwtValidator platformJwtValidator;
    private final PlatformJwtProperties platformJwtProperties;

    @GetMapping("/status")
    @PreAuthorize("hasRole('ADMIN')")
    public Map<String, Object> status() {
        long lastFetch = platformJwtValidator.lastFetchMillis();
        long nextRefresh = lastFetch > 0 ? lastFetch + platformJwtValidator.jwksTtlMillis() : 0L;

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("keyId", rsaSigningKeys.signingKey().getKeyID());
        body.put("algorithm", "RS256");
        body.put("jwksEndpoint", platformJwtProperties.jwksUrl());
        body.put("lastRefreshTimestamp",
                lastFetch > 0 ? Instant.ofEpochMilli(lastFetch).toString() : null);
        body.put("nextRefreshTimestamp",
                nextRefresh > 0 ? Instant.ofEpochMilli(nextRefresh).toString() : null);
        return body;
    }

    @PostMapping("/refresh")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<Map<String, Object>> refresh() {
        if (!StringUtils.hasText(platformJwtProperties.jwksUrl())) {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("message",
                    "JWKS refresh not supported in HMAC-only mode (no remote keys to refresh)");
            return ResponseEntity.status(HttpStatus.NOT_IMPLEMENTED).body(body);
        }
        boolean success = platformJwtValidator.refreshJwks();
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("refreshed", success);
        body.put("timestamp", Instant.now().toString());
        return ResponseEntity.ok(body);
    }
}
