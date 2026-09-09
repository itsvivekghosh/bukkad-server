package com.bhukkad.common.outbox;

import com.bhukkad.common.event.PlatformEventMessage;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class OutboxClientTest {

    @Mock
    private OutboxEventRepository repository;

    private OutboxClient client;

    @BeforeEach
    void beginTx() {
        // G-1 guard (PERF-2/D6): enqueue only runs inside a business tx.
        TransactionSynchronizationManager.setActualTransactionActive(true);
    }

    @AfterEach
    void endTx() {
        TransactionSynchronizationManager.setActualTransactionActive(false);
    }

    @Test
    void enqueue_withoutTransaction_throwsG1Violation() {
        TransactionSynchronizationManager.setActualTransactionActive(false);
        try {
            org.assertj.core.api.Assertions.assertThatThrownBy(() -> client().enqueue("OrderCreated", 1L, "{}"))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("G-1 violation");
            org.mockito.Mockito.verifyNoInteractions(repository);
        } finally {
            TransactionSynchronizationManager.setActualTransactionActive(true);
        }
    }

    private OutboxClient client() {
        return new OutboxClient(repository);
    }

    @Test
    void enqueue_savesPendingEvent() {
        client().enqueue("OrderCreated", 42L, "{\"id\":42}");

        ArgumentCaptor<OutboxEvent> captor = ArgumentCaptor.forClass(OutboxEvent.class);
        verify(repository).save(captor.capture());
        OutboxEvent saved = captor.getValue();
        assertThat(saved.getEventType()).isEqualTo("OrderCreated");
        assertThat(saved.getAggregateType()).isEqualTo(OutboxClient.AGGREGATE_DEFAULT);
        assertThat(saved.getAggregateId()).isEqualTo(42L);
        assertThat(saved.getPayload()).isEqualTo("{\"id\":42}");
        assertThat(saved.getStatus()).isEqualTo(OutboxEvent.OutboxStatus.PENDING);
    }

    @Test
    void enqueue_withExplicitAggregateType() {
        client().enqueue("MenuChanged", "MENU_ITEM", 7L, "{}");

        ArgumentCaptor<OutboxEvent> captor = ArgumentCaptor.forClass(OutboxEvent.class);
        verify(repository).save(captor.capture());
        assertThat(captor.getValue().getAggregateType()).isEqualTo("MENU_ITEM");
    }

    @Test
    void enqueue_envelope_embedsEnvelopeAsPayload() {
        PlatformEventMessage msg = PlatformEventMessage.of("OrderCreated", "99", "corr", "{\"total\":12}");
        client().enqueue(msg, 99L);

        ArgumentCaptor<OutboxEvent> captor = ArgumentCaptor.forClass(OutboxEvent.class);
        verify(repository).save(captor.capture());
        OutboxEvent saved = captor.getValue();
        assertThat(saved.getEventType()).isEqualTo("OrderCreated");
        assertThat(saved.getPayload()).contains("\"eventId\"");
        assertThat(saved.getPayload()).contains("\"correlationId\":\"corr\"");
    }

    @Test
    void enqueue_repositoryFailure_propagates() {
        doThrow(new IllegalStateException("db down")).when(repository).save(any(OutboxEvent.class));

        assertThatThrownBy(() -> client().enqueue("OrderCreated", 1L, "{}"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("db down");
    }
}
