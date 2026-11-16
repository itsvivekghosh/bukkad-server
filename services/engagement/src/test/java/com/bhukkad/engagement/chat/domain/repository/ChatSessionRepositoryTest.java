package com.bhukkad.engagement.chat.domain.repository;

import com.bhukkad.engagement.chat.AbstractChatPostgresTest;
import com.bhukkad.engagement.chat.domain.entity.ChatSession;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration tests for ChatSessionRepository.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({AbstractChatPostgresTest.class})
@EntityScan(basePackages = {"com.bhukkad.engagement.chat.domain.entity"})
class ChatSessionRepositoryTest extends AbstractChatPostgresTest {

    @Autowired
    private ChatSessionRepository sessionRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private ChatSession testSession;

    @BeforeEach
    void setUp() {
        // Create prerequisite customers and order
        jdbcTemplate.update("INSERT INTO customers (id, email, first_name, last_name, created_at, updated_at) VALUES (1, 'test@example.com', 'Test', 'Customer', NOW(), NOW()) ON CONFLICT (id) DO NOTHING");
        jdbcTemplate.update("INSERT INTO customers (id, email, first_name, last_name, created_at, updated_at) VALUES (2, 'test2@example.com', 'Test2', 'Customer2', NOW(), NOW()) ON CONFLICT (id) DO NOTHING");
        jdbcTemplate.update("INSERT INTO orders (id, customer_id, restaurant_id, status, total_amount, status_updated_at, created_at, updated_at) VALUES (1001, 1, 1, 'OPEN', 100.00, NOW(), NOW(), NOW()) ON CONFLICT (id) DO NOTHING");

        testSession = ChatSession.builder()
                .sessionId(UUID.randomUUID())
                .customerId(1L)
                .orderId(1001L)
                .department(ChatSession.Department.ORDER)
                .subject("Wrong order received")
                .status(ChatSession.Status.OPEN)
                .priority(ChatSession.Priority.HIGH)
                .createdAt(LocalDateTime.now().minusHours(2))
                .updatedAt(LocalDateTime.now().minusHours(2))
                .build();
        sessionRepository.save(testSession);
    }

    @Test
    @DisplayName("Should find session by session ID")
    void shouldFindSessionById() {
        // Act
        var result = sessionRepository.findBySessionId(testSession.getSessionId());

        // Assert
        assertThat(result).isPresent();
        assertThat(result.get().getCustomerId()).isEqualTo(1L);
        assertThat(result.get().getOrderId()).isEqualTo(1001L);
    }

    @Test
    @DisplayName("Should find sessions by customer ID")
    void shouldFindSessionsByCustomerId() {
        // Arrange
        ChatSession session2 = ChatSession.builder()
                .sessionId(UUID.randomUUID())
                .customerId(1L)
                .department(ChatSession.Department.GENERAL)
                .subject("Account question")
                .status(ChatSession.Status.OPEN)
                .priority(ChatSession.Priority.HIGH)
                .createdAt(LocalDateTime.now())
                .build();
        sessionRepository.save(session2);

        // Act
        List<ChatSession> result = sessionRepository.findByCustomerIdOrderByCreatedAtDesc(1L);

        // Assert
        assertThat(result).hasSize(2);
        assertThat(result.get(0).getSubject()).isEqualTo("Account question"); // Most recent first
    }

    @Test
    @DisplayName("Should find sessions by order ID")
    void shouldFindSessionsByOrderId() {
        // Act
        List<ChatSession> result = sessionRepository.findByOrderIdOrderByCreatedAtDesc(1001L);

        // Assert
        assertThat(result).hasSize(1);
        assertThat(result.get(0).getSessionId()).isEqualTo(testSession.getSessionId());
    }

    @Test
    @DisplayName("Should find sessions by department and status")
    void shouldFindSessionsByDepartmentAndStatus() {
        // Act
        List<ChatSession> result = sessionRepository.findByDepartmentAndStatusOrderByCreatedAtDesc(
                ChatSession.Department.ORDER, ChatSession.Status.OPEN);

        // Assert
        assertThat(result).hasSize(1);
        assertThat(result.get(0).getPriority()).isEqualTo(ChatSession.Priority.HIGH);
    }

    @Test
    @DisplayName("Should count sessions by status")
    void shouldCountSessionsByStatus() {
        // Arrange
        ChatSession resolvedSession = ChatSession.builder()
                .sessionId(UUID.randomUUID())
                .customerId(2L)
                .department(ChatSession.Department.DELIVERY)
                .subject("Delivery issue")
                .status(ChatSession.Status.RESOLVED)
                .priority(ChatSession.Priority.HIGH)
                .createdAt(LocalDateTime.now())
                .closedAt(LocalDateTime.now().plusHours(1))
                .build();
        sessionRepository.save(resolvedSession);

        // Act
        long openCount = sessionRepository.countByStatus(ChatSession.Status.OPEN);
        long resolvedCount = sessionRepository.countByStatus(ChatSession.Status.RESOLVED);

        // Assert
        assertThat(openCount).isEqualTo(1);
        assertThat(resolvedCount).isEqualTo(1);
    }

    @Test
    @DisplayName("Should delete session and cascade to messages")
    void shouldDeleteSessionAndCascadeToMessages() {
        UUID sessionId = testSession.getSessionId();

        // Act
        sessionRepository.deleteById(testSession.getId());

        // Assert
        assertThat(sessionRepository.findBySessionId(sessionId)).isEmpty();
    }
}