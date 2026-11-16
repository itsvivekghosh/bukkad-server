package com.bhukkad.common.outbox;

import java.time.Instant;

/**
 * Custom extensions for {@link OutboxEventRepository} that are implemented
 * directly against {@code JdbcTemplate} because the JPA native-query binding of
 * {@code java.time.LocalDateTime} to a PostgreSQL {@code TIMESTAMP} column does
 * not reliably evaluate the {@code processing_started_at < :staleBefore}
 * comparison inside the test's transaction snapshot. {@code JdbcTemplate}
 * binds {@code LocalDateTime} correctly, so crash recovery uses JDBC directly.
 */
public interface OutboxEventRepositoryCustom {

    /**
     * Crash recovery: rows left {@code PROCESSING} by a dead relay replica are
     * re-queued to {@code PENDING} immediately (no retry-count penalty).
     *
     * @param staleBefore rows with {@code processing_started_at} older than this
     *                    instant are considered stranded
     * @param note        error text recorded on the recovered row
     * @return the number of rows re-queued
     */
    int recoverStaleToPending(Instant staleBefore, String note);
}
