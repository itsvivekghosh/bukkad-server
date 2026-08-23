package com.bhukkad.config;

import com.bhukkad.entity.User;
import com.bhukkad.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.boot.ApplicationArguments;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.lang.reflect.Field;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DevAdminBootstrapTest {

    @Mock
    private UserRepository userRepository;

    private PasswordEncoder passwordEncoder;

    private DevAdminBootstrap bootstrap;

    @BeforeEach
    void setUp() throws Exception {
        passwordEncoder = new org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder();
        bootstrap = new DevAdminBootstrap(userRepository, passwordEncoder);
        setField(bootstrap, "adminEmail", "admin@bhukkad.dev");
        setField(bootstrap, "adminPassword", "Admin@123456");
    }

    @Test
    void run_skipsWhenAdminExists() {
        when(userRepository.existsByEmail("admin@bhukkad.dev")).thenReturn(true);

        bootstrap.run(mock(ApplicationArguments.class));

        verify(userRepository, never()).save(org.mockito.ArgumentMatchers.any(User.class));
    }

    @Test
    void run_seedsAdminWhenMissing() {
        when(userRepository.existsByEmail("admin@bhukkad.dev")).thenReturn(false);

        bootstrap.run(mock(ApplicationArguments.class));

        org.mockito.ArgumentCaptor<User> captor =
                org.mockito.ArgumentCaptor.forClass(User.class);
        verify(userRepository).save(captor.capture());
        User saved = captor.getValue();
        assertEquals("admin@bhukkad.dev", saved.getEmail());
        assertTrue(passwordEncoder.matches("Admin@123456", saved.getPassword()));
        assertEquals("Bhukkad Admin", saved.getFullName());
        assertEquals("9000000001", saved.getPhoneNumber());
        assertEquals(User.UserRole.ADMIN, saved.getRole());
        assertEquals(Boolean.TRUE, saved.getActive());
        assertEquals(Boolean.TRUE, saved.getEmailVerified());
    }

    private static void setField(Object target, String name, Object value) throws Exception {
        Field f = target.getClass().getDeclaredField(name);
        f.setAccessible(true);
        f.set(target, value);
    }
}
