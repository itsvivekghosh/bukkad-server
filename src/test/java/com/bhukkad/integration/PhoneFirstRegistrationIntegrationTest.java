package com.bhukkad.integration;

import com.bhukkad.entity.User;
import com.bhukkad.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.util.HashSet;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration tests for the phone-first registration schema changes (V58)
 * and the {@code UserRepository.findByPhoneNumber} method added for the
 * phone-first auth flow.
 *
 * <p>Shares the Testcontainers MySQL context from {@link AbstractJpaIntegrationTest}
 * to verify migrations apply cleanly and entity mappings align with the schema.</p>
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("test")
class PhoneFirstRegistrationIntegrationTest extends AbstractJpaIntegrationTest {

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private DataSource dataSource;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void cleanUsers() {
        jdbcTemplate.update("DELETE FROM users WHERE phone_verified = TRUE OR phone_verified IS NOT NULL");
    }

    @Test
    void v58_migration_addsPhoneVerificationColumns() throws Exception {
        Set<String> columns = new HashSet<>();
        try (Connection connection = connection()) {
            DatabaseMetaData meta = connection.getMetaData();
            try (ResultSet rs = meta.getColumns(null, null, "users", null)) {
                while (rs.next()) {
                    columns.add(rs.getString("COLUMN_NAME"));
                }
            }
        }

        assertThat(columns).contains("phone_verified", "profile_completed", "phone_verified_at");
    }

    @Test
    void v58_migration_makesEmailNullable() throws Exception {
        try (Connection connection = connection()) {
            DatabaseMetaData meta = connection.getMetaData();
            try (ResultSet rs = meta.getColumns(null, null, "users", "email")) {
                assertThat(rs.next()).isTrue();
                // IS_NULLABLE is "YES" when the column allows NULLs.
                assertThat(rs.getString("IS_NULLABLE")).isEqualTo("YES");
            }
        }
    }

    @Test
    void createPhoneFirstUser_savesWithNullEmailAndPhoneVerifiedFalse() {
        User user = new User();
        user.setPhoneNumber("9999999999");
        user.setEmail(null); // phone-first: no email yet
        user.setPassword(null); // password set later via profile completion
        user.setFullName(null);
        user.setRole(User.UserRole.CUSTOMER);
        user.setActive(true);
        user.setEmailVerified(false);
        user.setPhoneVerified(false);
        user.setProfileCompleted(false);

        User saved = userRepository.saveAndFlush(user);

        Optional<User> found = userRepository.findByPhoneNumber("9999999999");
        assertThat(found).isPresent();
        assertThat(found.get().getId()).isEqualTo(saved.getId());
        assertThat(found.get().getEmail()).isNull();
        assertThat(found.get().getPhoneVerified()).isFalse();
        assertThat(found.get().getProfileCompleted()).isFalse();
    }

    @Test
    void findByPhoneNumber_existingPhone_returnsUser() {
        User user = new User();
        user.setPhoneNumber("1111111111");
        user.setEmail("phone_1111111111@temp.bhukkad.local");
        user.setPassword("encoded");
        user.setFullName("Test User");
        user.setRole(User.UserRole.CUSTOMER);
        user.setActive(true);
        user.setPhoneVerified(true);
        userRepository.saveAndFlush(user);

        Optional<User> found = userRepository.findByPhoneNumber("1111111111");

        assertThat(found).isPresent();
        assertThat(found.get().getFullName()).isEqualTo("Test User");
        assertThat(found.get().getPhoneVerified()).isTrue();
    }

    @Test
    void findByPhoneNumber_nonExistentPhone_returnsEmpty() {
        Optional<User> found = userRepository.findByPhoneNumber("0000000000");
        assertThat(found).isEmpty();
    }

    @Test
    void findByEmailOrPhoneNumber_findsByEmail() {
        User user = new User();
        user.setPhoneNumber("3333333333");
        user.setEmail("findbyemail@test.com");
        user.setPassword("encoded");
        user.setRole(User.UserRole.CUSTOMER);
        user.setActive(true);
        user.setPhoneVerified(false);
        userRepository.saveAndFlush(user);

        Optional<User> found = userRepository.findByEmailOrPhoneNumber("findbyemail@test.com", "nonexistent");

        assertThat(found).isPresent();
        assertThat(found.get().getEmail()).isEqualTo("findbyemail@test.com");
    }

    @Test
    void findByEmailOrPhoneNumber_findsByPhone() {
        User user = new User();
        user.setPhoneNumber("4444444444");
        user.setEmail("phone_4444444444@temp.bhukkad.local");
        user.setPassword(null);
        user.setRole(User.UserRole.CUSTOMER);
        user.setActive(true);
        user.setPhoneVerified(false);
        userRepository.saveAndFlush(user);

        Optional<User> found = userRepository.findByEmailOrPhoneNumber("not-an-email", "4444444444");

        assertThat(found).isPresent();
        assertThat(found.get().getPhoneNumber()).isEqualTo("4444444444");
    }

    @Test
    void findByEmailOrPhoneNumber_nonExistent_returnsEmpty() {
        Optional<User> found = userRepository.findByEmailOrPhoneNumber("no@no.com", "0000000000");
        assertThat(found).isEmpty();
    }

    @Test
    void updateProfileCompleted_setsColumns() {
        User user = new User();
        user.setPhoneNumber("2222222222");
        user.setEmail(null);
        user.setFullName(null);
        user.setPassword(null);
        user.setRole(User.UserRole.CUSTOMER);
        user.setActive(true);
        user.setPhoneVerified(true);
        user.setProfileCompleted(false);
        User saved = userRepository.saveAndFlush(user);

        saved.setEmail("user@example.com");
        saved.setFullName("Completed User");
        saved.setPassword("encoded_pw");
        saved.setProfileCompleted(true);
        userRepository.saveAndFlush(saved);

        Optional<User> fetched = userRepository.findById(saved.getId());
        assertThat(fetched).isPresent();
        assertThat(fetched.get().getEmail()).isEqualTo("user@example.com");
        assertThat(fetched.get().getFullName()).isEqualTo("Completed User");
        assertThat(fetched.get().getProfileCompleted()).isTrue();
    }

    private Connection connection() throws Exception {
        return dataSource.getConnection();
    }
}
