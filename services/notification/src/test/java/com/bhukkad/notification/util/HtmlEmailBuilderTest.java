package com.bhukkad.notification.util;

import com.bhukkad.notification.util.HtmlEmailBuilder.KeyValuePair;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Transactional email template: dark-mode blocks, optional sections,
 * line-item filtering and conservative HTML escaping of user strings.
 */
class HtmlEmailBuilderTest {

    private final HtmlEmailBuilder builder = new HtmlEmailBuilder();

    @Test
    void fullRender_containsAllSections() {
        String html = builder.orderEmail(
                "Order confirmed",
                "Your biryani is on the way",
                List.of(new KeyValuePair("Order", "#1441"), new KeyValuePair("ETA", "25 min")),
                List.of("1x Butter Chicken", "2x Naan"),
                "https://bhukkad.app/orders/1441",
                "Track order");

        assertThat(html)
                .contains("<!DOCTYPE html>")
                .contains("@media (prefers-color-scheme: dark)")
                .contains("supported-color-schemes")
                .contains("<h1 style=\"margin:0 0 12px;color:#1c1c1e;font-size:22px;line-height:1.3;\">Order confirmed</h1>")
                .contains("<p ")
                .contains(">Your biryani is on the way</p>")
                .contains("summary-label")
                .contains(">Order</td>")
                .contains(">#1441</td>")
                .contains("<ul class=\"items\"")
                .contains("<li style=\"color:#48484a")
                .contains(">1x Butter Chicken</li>")
                .contains("class=\"cta\"")
                .contains("href=\"https://bhukkad.app/orders/1441\"")
                .contains(">Track order</a>")
                .contains("&copy; Bhukkad");
    }

    @Test
    void optionalSectionsOmitted_whenNull() {
        String html = builder.orderEmail("Hi", null, null, null, null, null);

        assertThat(html).contains(">Hi</h1>");
        assertThat(html).doesNotContain("<p style=\"margin:0 0 18px");
        assertThat(html).doesNotContain("class=\"summary\"");
        assertThat(html).doesNotContain("<ul");
        assertThat(html).doesNotContain("class=\"cta\"");
    }

    @Test
    void blankPartsAlsoOmitted() {
        String html = builder.orderEmail("Hi", "   ", List.of(), Arrays.asList(" ", "real"), "  ", "  ");

        assertThat(html).doesNotContain(">   </p>");
        assertThat(html).doesNotContain("class=\"summary\"");
        // blank items filtered, non-blank kept; blank ctaUrl suppresses the button
        assertThat(html).doesNotContain("<li style=\"color:#48484a;font-size:14px;padding:2px 0;\"> </li>");
        assertThat(html).contains(">real</li>");
        assertThat(html).doesNotContain("class=\"cta\"");
    }

    @Test
    void nullHeading_escapedAsEmpty() {
        String html = builder.orderEmail(null, null, null, null, null, null);
        assertThat(html).contains("<h1 style=\"margin:0 0 12px;color:#1c1c1e;font-size:22px;line-height:1.3;\"></h1>");
    }

    @Test
    void userStringsAreHtmlEscaped() {
        String html = builder.orderEmail(
                "<script>alert(1)</script>",
                "a & b \"quoted\"",
                List.of(new KeyValuePair("<b>label</b>", "<i>value</i>")),
                List.of("<li>inject</li>"),
                "https://x/?a=1&b=2",
                "\"Click & go\"");

        assertThat(html).doesNotContain("<script>");
        assertThat(html).contains("&lt;script&gt;alert(1)&lt;/script&gt;");
        assertThat(html).contains("a &amp; b &quot;quoted&quot;");
        assertThat(html).contains("&lt;b&gt;label&lt;/b&gt;");
        assertThat(html).contains("&lt;i&gt;value&lt;/i&gt;");
        assertThat(html).contains("href=\"https://x/?a=1&amp;b=2\"");
        assertThat(html).contains("&quot;Click &amp; go&quot;");
    }

    @Test
    void summarySkipsNullPairsAndNullLabels_keepsNullValues() {
        String html = builder.orderEmail(
                "t", null,
                Arrays.asList(null, new KeyValuePair(null, "orphan"), new KeyValuePair("Total", null)),
                null, "https://cta", null);

        assertThat(html).doesNotContain(">orphan");
        assertThat(html).contains(">Total</td>");
        // null value renders an empty summary cell
        assertThat(html).contains("font-weight:600;\"></td>");
        assertThat(html).doesNotContain("null<");
        // null label falls back to default caption
        assertThat(html).contains(">View details</a>");
    }
}
