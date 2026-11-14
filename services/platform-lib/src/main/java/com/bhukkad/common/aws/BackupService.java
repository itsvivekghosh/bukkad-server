package com.bhukkad.common.aws;

import org.springframework.stereotype.Service;
import software.amazon.awssdk.services.backup.BackupClient;
import software.amazon.awssdk.services.backup.model.*;

/**
 * Backup service for backup and restore management.
 * <p>
 * Provides operations for managing AWS Backup backup plans, vaults,
 * and recovery points. Fully testable against Floci emulator locally.
 */
@Service
public class BackupService {

    private final BackupClient backupClient;
    private final AwsProperties awsProperties;

    public BackupService(BackupClient backupClient, AwsProperties awsProperties) {
        this.backupClient = backupClient;
        this.awsProperties = awsProperties;
    }

    /**
     * Creates a backup vault.
     */
    public CreateBackupVaultResponse createBackupVault(String vaultName) {
        return backupClient.createBackupVault(CreateBackupVaultRequest.builder()
                .backupVaultName(vaultName)
                .build());
    }

    /**
     * Lists all backup vaults.
     */
    public ListBackupVaultsResponse listBackupVaults() {
        return backupClient.listBackupVaults(ListBackupVaultsRequest.builder().build());
    }

    /**
     * Describes a backup vault.
     */
    public DescribeBackupVaultResponse describeBackupVault(String vaultName) {
        return backupClient.describeBackupVault(DescribeBackupVaultRequest.builder()
                .backupVaultName(vaultName)
                .build());
    }

    /**
     * Deletes a backup vault.
     */
    public DeleteBackupVaultResponse deleteBackupVault(String vaultName) {
        return backupClient.deleteBackupVault(DeleteBackupVaultRequest.builder()
                .backupVaultName(vaultName)
                .build());
    }

    /**
     * Lists recovery points in a vault.
     */
    public ListRecoveryPointsByBackupVaultResponse listRecoveryPoints(String vaultName) {
        return backupClient.listRecoveryPointsByBackupVault(ListRecoveryPointsByBackupVaultRequest.builder()
                .backupVaultName(vaultName)
                .build());
    }
}