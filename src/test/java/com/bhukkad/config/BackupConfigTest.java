package com.bhukkad.config;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Validates the automated backup scripts and K8s CronJob configuration
 * required for PostgreSQL + Redis disaster recovery.
 */
class BackupConfigTest {

    private static final Path POSTGRES_SCRIPT = Paths.get("docker/scripts/backup-postgres.sh");
    private static final Path REDIS_SCRIPT = Paths.get("docker/scripts/backup-redis.sh");
    private static final Path CRONJOB = Paths.get("k8s/backup-cronjob.yaml");
    private static final Path BACKUP_SCRIPTS_CONFIGMAP = Paths.get("k8s/backup-scripts.yaml");

    @Test
    void postgresBackupScript_existsAndHasRequiredContent() throws IOException {
        assertTrue(Files.exists(POSTGRES_SCRIPT), "PostgreSQL backup script must exist");
        String script = Files.readString(POSTGRES_SCRIPT, StandardCharsets.UTF_8);
        assertTrue(script.contains("pg_dump"), "Must use pg_dump");
        assertTrue(script.contains("--no-owner"), "Must use --no-owner for portability");
        assertTrue(script.contains("--no-acl"), "Must use --no-acl for portability");
        assertTrue(script.contains("gzip"), "Must compress output");
        assertTrue(script.contains("RETENTION_DAYS"), "Must support retention rotation");
        assertTrue(script.contains("aws s3 cp"), "Must support S3 off-site upload");
    }

    @Test
    void redisBackupScript_existsAndHasRequiredContent() throws IOException {
        assertTrue(Files.exists(REDIS_SCRIPT), "Redis backup script must exist");
        String script = Files.readString(REDIS_SCRIPT, StandardCharsets.UTF_8);
        assertTrue(script.contains("BGSAVE"), "Must trigger BGSAVE");
        assertTrue(script.contains("LASTSAVE"), "Must poll LASTSAVE for completion");
        assertTrue(script.contains("dump.rdb"), "Must archive RDB file");
        assertTrue(script.contains("RETENTION_DAYS"), "Must support retention rotation");
        assertTrue(script.contains("aws s3 cp"), "Must support S3 off-site upload");
    }

    @Test
    void k8sBackupCronjob_existsAndValid() throws IOException {
        assertTrue(Files.exists(CRONJOB), "K8s backup CronJob must exist");
        String yaml = Files.readString(CRONJOB, StandardCharsets.UTF_8);
        assertTrue(yaml.contains("kind: CronJob"), "Must be a CronJob");
        assertTrue(yaml.contains("schedule:"), "Must have a schedule");
        assertTrue(yaml.contains("postgres-backup"), "Must have PostgreSQL backup container");
        assertTrue(yaml.contains("redis-backup"), "Must have Redis backup container");
        assertTrue(yaml.contains("concurrencyPolicy: Forbid"), "Must not run concurrent backups");
        assertTrue(yaml.contains("successfulJobsHistoryLimit"), "Must limit job history");
        assertTrue(yaml.contains("failedJobsHistoryLimit"), "Must limit failed job history");
        assertTrue(yaml.contains("defaultMode: 0755"), "Scripts must be executable in CronJob");
    }

    @Test
    void backupScriptsConfigmap_exists() throws IOException {
        assertTrue(Files.exists(BACKUP_SCRIPTS_CONFIGMAP), "Backup scripts ConfigMap must exist");
        String yaml = Files.readString(BACKUP_SCRIPTS_CONFIGMAP, StandardCharsets.UTF_8);
        assertTrue(yaml.contains("backup-postgres.sh"), "Must contain PostgreSQL script");
        assertTrue(yaml.contains("backup-redis.sh"), "Must contain Redis script");
    }

    @Test
    void dockerCompose_hasBackupService() throws IOException {
        Path compose = Paths.get("docker/docker-compose.prod.yml");
        assertTrue(Files.exists(compose), "docker-compose.prod.yml must exist");
        String yaml = Files.readString(compose, StandardCharsets.UTF_8);
        assertTrue(yaml.contains("backup:"), "Must have backup service");
        assertTrue(yaml.contains("backup-postgres.sh"), "Must mount PostgreSQL backup script");
        assertTrue(yaml.contains("backup-redis.sh"), "Must mount Redis backup script");
    }
}
