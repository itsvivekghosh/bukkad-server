package com.bhukkad.common.outbox;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * JDBC-based implementation of {@link OutboxEventRepositoryCustom}. Crash
 * recovery is executed through {@code JdbcTemplate} (not a JPA native query)
 * because the JPA binding of {@code java.time.LocalDateTime} to a PostgreSQL
 * {@code TIMESTAMP} column does not reliably evaluate the
 * {@code processing_started_at < :staleBefore} comparison inside the caller's
 * transaction snapshot. {@code JdbcTemplate} binds {@code LocalDateTime}
 * correctly, so this path is used for crash recovery.
 */
@Repository
public class OutboxEventRepositoryImpl implements OutboxEventRepositoryCustom {

    private final JdbcTemplate jdbcTemplate;

    public OutboxEventRepositoryImpl(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public int recoverStaleToPending(Instant staleBefore, String note) {
        LocalDateTime staleBeforeLocal = staleBefore.atZone(ZoneId.systemDefault()).toLocalDateTime();
        return jdbcTemplate.update(
                "UPDATE outbox_events SET status = 'PENDING', processing_started_at = NULL, " +
                        "next_attempt_at = NULL, last_error = ? " +
                        "WHERE status = 'PROCESSING' AND processing_started_at < ?",
                note, staleBeforeLocal);
    }
}
