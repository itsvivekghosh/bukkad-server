package com.bhukkad.security;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Scheduled RSA key pair rotation for client-side password encryption.
 * Runs every 24 hours to limit the window of any undetected compromise.
 *
 * <p>The rotation is guarded by {@link net.javacrumbs.shedlock.spring.annotation.SchedulerLock}
 * so only one replica in a multi-pod deployment performs the rotation. The new
 * key pair is persisted to Redis (shared across all replicas) and the previous
 * key remains available for a grace window so in-flight JWEs still decrypt
 * ({@link ClientEncryptionKeyService} @{@link JwePasswordCrypto}).</p>
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class ClientEncryptionKeyRotationScheduler {

    private final ClientEncryptionKeyService keyService;

    /** Rotate the encryption key pair daily at midnight UTC. */
    @Scheduled(cron = "0 0 0 * * *", zone = "UTC")
    @SchedulerLock(name = "client-encryption-key-rotation", lockAtMostFor = "PT1H", lockAtLeastFor = "PT10M")
    public void rotateKeys() {
        keyService.generateKeyPair();
    }
}
