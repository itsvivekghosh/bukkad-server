package com.bhukkad.testsupport;

import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;

import java.lang.annotation.*;

/**
 * Annotation for service layer tests.
 * Loads only the service layer with mocked dependencies.
 */
@Target({ElementType.TYPE})
@Retention(RetentionPolicy.RUNTIME)
@Import({ServiceTestConfig.class})
public @interface ServiceTest {
	Class<?>[] value() default {};
}