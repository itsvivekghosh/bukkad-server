package com.bhukkad;

import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.springframework.boot.SpringApplication;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mockStatic;

class BackendServerApplicationTests {

    @Test
    void constructor_isInvoked() {
        new BackendServerApplication();
    }

    @Test
    void main_startsSpringApplication() {
        try (MockedStatic<SpringApplication> mocked = mockStatic(SpringApplication.class)) {
            mocked.when(() -> SpringApplication.run(eq(BackendServerApplication.class), any(String[].class)))
                    .thenReturn(null);

            BackendServerApplication.main(new String[]{"--test"});

            mocked.verify(() -> SpringApplication.run(BackendServerApplication.class, new String[]{"--test"}));
        }
    }
}
