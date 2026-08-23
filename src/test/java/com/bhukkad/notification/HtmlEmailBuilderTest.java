package com.bhukkad.notification;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HtmlEmailBuilderTest {

    private final HtmlEmailBuilder builder = new HtmlEmailBuilder();

    @Test
    void orderEmail_containsDarkModeSupport() {
        String html = builder.orderEmail("Order confirmed", "Thanks!",
                List.of(new HtmlEmailBuilder.KeyValuePair("Order", "BK-1")),
                null, "https://bhukkad.com/orders/1", "Track order");

        assertTrue(html.contains("prefers-color-scheme: dark"),
                "must honour OS dark mode");
        assertTrue(html.contains("color-scheme"));
        assertTrue(html.contains("<!DOCTYPE html>"));
    }

    @Test
    void orderEmail_escapesUserSuppliedValues() {
        String html = builder.orderEmail("<script>alert(1)</script>", null,
                List.of(new HtmlEmailBuilder.KeyValuePair("Name", "<b>bold</b>")),
                null, null, null);

        assertFalse(html.contains("<script>alert"), "user input must be escaped");
        assertTrue(html.contains("&lt;script&gt;"));
    }

    @Test
    void orderEmail_rendersSummaryRowsAndItems() {
        String html = builder.orderEmail("Update", "Body",
                List.of(new HtmlEmailBuilder.KeyValuePair("Order", "BK-9"),
                        new HtmlEmailBuilder.KeyValuePair("Total", "\u20B9250")),
                List.of("Butter Chicken x2"), "https://bhukkad.com", "Track");

        assertTrue(html.contains("BK-9"));
        assertTrue(html.contains("\u20B9250"));
        assertTrue(html.contains("Butter Chicken x2"));
        assertTrue(html.contains("href=\"https://bhukkad.com\""));
    }
}
