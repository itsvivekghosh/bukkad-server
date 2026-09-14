package com.bhukkad.common.datasource;

import java.lang.annotation.*;

/**
 * Marks a repository method (or type) as sharded by the first long argument
 * ({@code userId}). The {@link ShardAspect} intercepts the call and sets
 * {@code search_path} on the current JDBC connection before the method executes.
 */
@Target({ElementType.METHOD, ElementType.TYPE})
@Retention(RetentionPolicy.RUNTIME)
public @interface Shard {
    /**
     * The logical shard key name (used for audit/logging only; the actual
     * schema is derived from the first long argument by {@link ShardRouter}).
     */
    String key();
}
