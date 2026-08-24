package com.bhukkad.audit;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a method as one that should write an audit event after it completes normally.
 *
 * <p>{@link #resourceId()}, {@link #oldState()} and {@link #newState()} are SpEL expressions
 * evaluated against the method arguments, e.g. {@code "#request.orderId"} or {@code "''"} when
 * not applicable. Expression evaluation failures never fail the audited method.</p>
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface Audited {

    /** What happened, e.g. {@code REFUND}. */
    String action();

    /** Kind of resource, e.g. {@code PAYMENT}. */
    String resourceType();

    /** SpEL expression for the resource identifier, evaluated against the method arguments. */
    String resourceId() default "";

    /** SpEL expression for the pre-change state, evaluated against the method arguments. */
    String oldState() default "";

    /** SpEL expression for the post-change state, evaluated against the method arguments. */
    String newState() default "";
}
