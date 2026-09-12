package com.bhukkad.testsupport;

import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;

import java.lang.annotation.*;

/**
 * Annotation for controller slice tests.
 * Configures MockMvc with only the web layer loaded.
 */
@Target({ElementType.TYPE})
@Retention(RetentionPolicy.RUNTIME)
@WebMvcTest
@Import({ControllerTestConfig.class})
public @interface ControllerTest {
	Class<?>[] value() default {};
}