package com.bhukkad.restaurant.testsupport;

import org.springframework.beans.factory.ObjectProvider;

/**
 * Minimal {@link ObjectProvider} stand-in for standalone unit tests: returns
 * the given bean (or reports it absent when {@code null}).
 */
public final class FixedObjectProvider<T> implements ObjectProvider<T> {

    private final T bean;

    public FixedObjectProvider(T bean) {
        this.bean = bean;
    }

    @Override
    public T getObject(Object... args) {
        return getObject();
    }

    @Override
    public T getObject() {
        if (bean == null) {
            throw new IllegalStateException("No such bean");
        }
        return bean;
    }

    @Override
    public T getIfAvailable() {
        return bean;
    }

    @Override
    public T getIfUnique() {
        return bean;
    }
}
