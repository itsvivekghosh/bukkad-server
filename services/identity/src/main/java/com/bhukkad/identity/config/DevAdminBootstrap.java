package com.bhukkad.identity.config;

import com.bhukkad.identity.domain.Admin;
import com.bhukkad.identity.domain.AdminRepository;
import com.bhukkad.identity.domain.User;
import com.bhukkad.identity.security.PasswordService;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Dev-only admin seeding for local runs / API tests. Enabled explicitly with
 * {@code app.bootstrap-admin.enabled=true} (never set in prod profiles).
 */
@Component
@ConditionalOnProperty(name = "app.bootstrap-admin.enabled", havingValue = "true")
public class DevAdminBootstrap implements ApplicationRunner {
    private static final Logger log = LoggerFactory.getLogger(DevAdminBootstrap.class);

    private final AdminRepository adminRepository;
    private final PasswordService passwordService;
    private final String email;
    private final String password;
    private final String fullName;

    @PersistenceContext
    private EntityManager em;

    public DevAdminBootstrap(AdminRepository adminRepository,
                             PasswordService passwordService,
                             @Value("${app.bootstrap-admin.email:admin@bhukkad.dev}") String email,
                             @Value("${app.bootstrap-admin.password:Test@123456}") String password,
                             @Value("${app.bootstrap-admin.full-name:Platform Admin}") String fullName) {
        this.adminRepository = adminRepository;
        this.passwordService = passwordService;
        this.email = email;
        this.password = password;
        this.fullName = fullName;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (adminRepository.findByEmail(email).isPresent()) {
            log.info("DEV_ADMIN_PRESENT email={}", email);
            return;
        }
        // Registration writes users rows with explicit ids (OVERRIDING SYSTEM
        // VALUE), which leaves users_id_seq stale below max(id). Re-arm the
        // sequence so this identity-generated insert cannot collide with an
        // existing users_pkey.
        em.createNativeQuery("SELECT setval('users_id_seq', "
                        + "GREATEST((SELECT COALESCE(MAX(id), 0) FROM users), 1))")
                .getSingleResult();
        Admin admin = new Admin();
        admin.setEmail(email);
        admin.setFullName(fullName);
        admin.setPassword(passwordService.hash(password));
        admin.setRole(User.UserRole.ADMIN);
        adminRepository.save(admin);
        // Registration shares users.id == customers.id (resolveScope depends on
        // it). The admin id just consumed from users_id_seq must therefore be
        // reserved from customers_id_seq too, or the next customer registration
        // reuses it and hits users_pkey duplicate.
        em.createNativeQuery("SELECT setval('customers_id_seq', "
                        + "GREATEST((SELECT COALESCE(MAX(id), 0) FROM customers), :adminId))")
                .setParameter("adminId", admin.getId())
                .getSingleResult();
        log.warn("DEV_ADMIN_SEEDED email={} (bootstrap admin created for local/API-test use)", email);
    }
}
