package com.bhukkad.notification;

import com.bhukkad.notification.domain.entity.Notification;
import com.bhukkad.notification.domain.repository.NotificationRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class NotificationRepositoryPostgresIntegrationTest extends AbstractNotificationPostgresTest {

    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private NotificationRepository repository;

    @BeforeEach
    void clean() {
        jdbcTemplate.update("DELETE FROM notifications");
    }

    @Test
    void migration_applied() {
        Integer tables = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM information_schema.tables WHERE table_schema = current_schema() " +
                        "AND table_name IN ('notifications')", Integer.class);
        assertThat(tables).isEqualTo(1);
    }

    @Test
    void saveAndFindByRecipientChannel() {
        Notification n = new Notification();
        n.setChannel("EMAIL");
        n.setRecipient("a@b.com");
        n.setStatus(Notification.STATUS_SENT);
        repository.saveAndFlush(n);

        assertThat(repository.findByRecipientAndChannel("a@b.com", "EMAIL")).hasSize(1);
    }
}
