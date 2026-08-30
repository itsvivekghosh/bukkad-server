package com.bhukkad.config;

import com.bhukkad.entity.Admin;
import com.bhukkad.repository.AdminRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

/**
 * Seeds a default admin account in non-production profiles for local API testing.
 * Credentials live on the segregated admins table (V62); the registry row
 * carries identity and account state.
 */
@Slf4j
@Component
@Profile({"dev", "docker", "default"})
@RequiredArgsConstructor
public class DevAdminBootstrap implements ApplicationRunner {

    private final AdminRepository adminRepository;
    private final PasswordEncoder passwordEncoder;

    @Value("${app.bootstrap.admin.email:admin@bhukkad.dev}")
    private String adminEmail;

    @Value("${app.bootstrap.admin.password:Admin@123456}")
    private String adminPassword;

    @Override
    public void run(ApplicationArguments args) {
        if (Boolean.TRUE.equals(adminRepository.existsByEmail(adminEmail))) {
            return;
        }

        Admin admin = new Admin();
        admin.setEmail(adminEmail);
        admin.setPassword(passwordEncoder.encode(adminPassword));
        admin.setFullName("Bhukkad Admin");
        admin.setPhoneNumber("9000000001");
        admin.setRole(Admin.UserRole.ADMIN);
        admin.setActive(true);
        admin.setEmailVerified(true);

        adminRepository.save(admin);
        log.info("Seeded dev admin user | email={}", adminEmail);
    }
}
