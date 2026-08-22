package com.bhukkad.chaos;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a method as a chaos-fault injection point. No effect unless
 * {@code app.chaos.enabled=true}.
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface ChaosFault {
    String value() default "";
}
