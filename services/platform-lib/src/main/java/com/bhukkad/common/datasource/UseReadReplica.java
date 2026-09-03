package com.bhukkad.common.datasource;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a service method (or class whose methods) may be served from a read
 * replica (port of {@code com.bhukkad.datasource.UseReadReplica}). Type-level
 * usage applies to every method in the class.
 */
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.METHOD, ElementType.TYPE})
public @interface UseReadReplica {
}
