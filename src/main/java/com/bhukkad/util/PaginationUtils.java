package com.bhukkad.util;

import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;

public final class PaginationUtils {

    public static final int DEFAULT_PAGE_SIZE = 20;
    public static final int MAX_PAGE_SIZE = 100;
    public static final int KITCHEN_QUEUE_DEFAULT_LIMIT = 50;
    public static final int KITCHEN_QUEUE_MAX_LIMIT = 200;

    private PaginationUtils() {}

    public static Pageable page(int page, int size, Sort sort) {
        int safePage = Math.max(page, 0);
        int safeSize = Math.min(Math.max(size, 1), MAX_PAGE_SIZE);
        return PageRequest.of(safePage, safeSize, sort);
    }

    public static Pageable page(int page, int size, int maxSize, Sort sort) {
        int safePage = Math.max(page, 0);
        int safeSize = Math.min(Math.max(size, 1), maxSize);
        return PageRequest.of(safePage, safeSize, sort);
    }

    public static Pageable limited(int limit, Sort sort) {
        int safeLimit = Math.min(Math.max(limit, 1), KITCHEN_QUEUE_MAX_LIMIT);
        return PageRequest.of(0, safeLimit, sort);
    }
}
