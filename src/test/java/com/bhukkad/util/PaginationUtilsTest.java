package com.bhukkad.util;

import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;

import static org.junit.jupiter.api.Assertions.assertEquals;

class PaginationUtilsTest {

    @Test
    void page_clampsNegativePageAndOversizedLimit() {
        Pageable pageable = PaginationUtils.page(-1, 500, Sort.by("createdAt"));

        assertEquals(0, pageable.getPageNumber());
        assertEquals(PaginationUtils.MAX_PAGE_SIZE, pageable.getPageSize());
    }

    @Test
    void page_withCustomMax_clampsToMax() {
        Pageable pageable = PaginationUtils.page(0, 0, 15, Sort.by("id"));

        assertEquals(1, pageable.getPageSize());
    }

    @Test
    void limited_clampsKitchenQueueSize() {
        Pageable pageable = PaginationUtils.limited(999, Sort.by(Sort.Direction.ASC, "createdAt"));

        assertEquals(PaginationUtils.KITCHEN_QUEUE_MAX_LIMIT, pageable.getPageSize());
        assertEquals(0, pageable.getPageNumber());
    }
}
