package com.bhukkad.config;

import com.bhukkad.entity.User;
import com.bhukkad.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

/**
 * Seeds a default admin user in non-production profiles for local API testing.
 */
@Slf4j
@Component
@Profile({"dev", "docker", "default"})
@RequiredArgsConstructor
public class DevAdminBootstrap implements ApplicationRunner {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;

    @Value("${app.bootstrap.admin.email:admin@bhukkad.dev}")
    private String adminEmail;

    @Value("${app.bootstrap.admin.password:Admin@123456}")
    private String adminPassword;

    @Override
    public void run(ApplicationArguments args) {
        if (userRepository.existsByEmail(adminEmail)) {
            return;
        }

        User admin = new User();
        admin.setEmail(adminEmail);
        admin.setPassword(passwordEncoder.encode(adminPassword));
        admin.setFullName("Bhukkad Admin");
        admin.setPhoneNumber("9000000001");
        admin.setRole(User.UserRole.ADMIN);
        admin.setActive(true);
        admin.setEmailVerified(true);

        userRepository.save(admin);
        log.info("Seeded dev admin user | email={}", adminEmail);
    }
}
