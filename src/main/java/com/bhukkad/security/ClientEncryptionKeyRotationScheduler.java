package com.bhukkad.security;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Scheduled RSA key pair rotation for client-side password encryption.
 * Runs every 24 hours to limit the window of any undetected compromise.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class ClientEncryptionKeyRotationScheduler {

    private final ClientEncryptionKeyService keyService;

    /**
     * Rotate the encryption key pair daily at midnight UTC.
     */
    @Scheduled(cron = "0 0 0 * * *", zone = "UTC")
    public void rotateKeys() {
        keyService.generateKeyPair();
    }
}
