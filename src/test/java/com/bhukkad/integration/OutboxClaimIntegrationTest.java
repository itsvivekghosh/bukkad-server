package com.bhukkad.integration;

import com.bhukkad.common.outbox.OutboxEvent;
import com.bhukkad.common.outbox.OutboxEventRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Validates the outbox claim-and-process semantics against a real MySQL 8
 * database: the {@code FOR UPDATE SKIP LOCKED} claim query, the
 * {@code PROCESSING} status transition, and the stale-processing reset.
 * <p>
 * The V57 migration ({@code processing_started_at} column) is also exercised
 * — if it has not been applied the insert will fail, catching schema drift.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("test")
class OutboxClaimIntegrationTest extends AbstractJpaIntegrationTest {

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private OutboxEventRepository outboxEventRepository;

    @BeforeEach
    void cleanTestData() {
        jdbcTemplate.update("DELETE FROM outbox_events");
    }

    @Test
    void claimPending_returnsOnlyPendingRows() {
        insertEvent("ORDER_CREATED", OutboxEvent.OutboxStatus.PENDING, LocalDateTime.now().minusMinutes(5));
        insertEvent("ORDER_STATUS_CHANGED", OutboxEvent.OutboxStatus.PUBLISHED, LocalDateTime.now().minusMinutes(5));

        List<OutboxEvent> claimed = outboxEventRepository.findPendingForProcessing(
                OutboxEvent.OutboxStatus.PENDING.name(), 50);

        assertThat(claimed).hasSize(1);
        assertThat(claimed.get(0).getEventType()).isEqualTo("ORDER_CREATED");
    }

    @Test
    void claimAfterMarkingProcessing_returnsEmpty() {
        OutboxEvent event = insertEvent("ORDER_CREATED", OutboxEvent.OutboxStatus.PENDING,
                LocalDateTime.now().minusMinutes(5));
        event.setStatus(OutboxEvent.OutboxStatus.PROCESSING);
        event.setProcessingStartedAt(LocalDateTime.now());
        outboxEventRepository.save(event);

        List<OutboxEvent> claimed = outboxEventRepository.findPendingForProcessing(
                OutboxEvent.OutboxStatus.PENDING.name(), 50);

        assertThat(claimed).isEmpty();
    }

    @Test
    void staleProcessingEvents_areFoundByQuery() {
        LocalDateTime staleCutoff = LocalDateTime.now().minusMinutes(30);
        OutboxEvent stale = insertEvent("ORDER_CREATED", OutboxEvent.OutboxStatus.PROCESSING,
                LocalDateTime.now().minusHours(1));
        OutboxEvent fresh = insertEvent("ORDER_STATUS_CHANGED", OutboxEvent.OutboxStatus.PROCESSING,
                LocalDateTime.now());

        List<OutboxEvent> staleEvents = outboxEventRepository.findStaleProcessing(
                OutboxEvent.OutboxStatus.PROCESSING, staleCutoff);

        assertThat(staleEvents).extracting(OutboxEvent::getId)
                .contains(stale.getId())
                .doesNotContain(fresh.getId());
    }

    @Test
    void v57ProcessingStartedAtColumnExists() {
        OutboxEvent event = insertEvent("ORDER_CREATED", OutboxEvent.OutboxStatus.PROCESSING, LocalDateTime.now());
        OutboxEvent readBack = outboxEventRepository.findById(event.getId()).orElseThrow();
        assertThat(readBack.getProcessingStartedAt()).isNotNull();
    }

    private OutboxEvent insertEvent(String eventType, OutboxEvent.OutboxStatus status, LocalDateTime createdAt) {
        OutboxEvent event = new OutboxEvent();
        event.setEventType(eventType);
        event.setAggregateType("ORDER");
        event.setAggregateId(1L);
        event.setPayload("{\"test\":true}");
        event.setStatus(status);
        event.setCreatedAt(createdAt);
        if (status == OutboxEvent.OutboxStatus.PROCESSING) {
            event.setProcessingStartedAt(createdAt);
        }
        return outboxEventRepository.saveAndFlush(event);
    }
}