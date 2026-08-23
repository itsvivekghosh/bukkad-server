package com.bhukkad.notification;

import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Builds responsive HTML email bodies with OS dark-mode support (FEATURE #13).
 *
 * <p>Email clients vary wildly, so the markup is deliberately conservative:</p>
 * <ul>
 *   <li>Table-based layout (Outlook-safe) with a single outer wrapper.</li>
 *   <li>{@code @media (prefers-color-scheme: dark)} overrides background/text
 *       colors for clients that honour the OS theme (Apple Mail, iOS Mail, Gmail
 *       Android app). {@code color-scheme} / {@code supported-color-schemes} meta
 *       hints tell clients an adaptive palette exists.</li>
 *   <li>Inline styles carry the light-mode default; the media query only flips
 *       colors, never layout.</li>
 * </ul>
 */
@Component
public class HtmlEmailBuilder {

    private static final String BRAND = "#ff6d3f";

    /**
     * Renders the standard transactional template: brand header, heading,
     * optional key/value summary rows and a list of line items.
     *
     * @param heading   main message line, e.g. "Order confirmed"
     * @param bodyText  supporting paragraph (plain text; rendered in a &lt;p&gt;)
     * @param summary   ordered label → value rows (order number, ETA, total, …);
     *                  insertion order preserved
     * @param items     optional line-item descriptions shown as a bulleted list
     * @param ctaUrl    optional call-to-action target; when null no button renders
     * @param ctaLabel  button caption when {@code ctaUrl} is present
     */
    public String orderEmail(String heading,
                             String bodyText,
                             List<KeyValuePair> summary,
                             List<String> items,
                             String ctaUrl,
                             String ctaLabel) {
        StringBuilder sb = new StringBuilder(2048);
        sb.append("<!DOCTYPE html>\n<html lang=\"en\">\n<head>\n")
          .append("<meta charset=\"utf-8\">\n")
          .append("<meta name=\"viewport\" content=\"width=device-width, initial-scale=1\">\n")
          .append("<meta name=\"color-scheme\" content=\"light dark\">\n")
          .append("<meta name=\"supported-color-schemes\" content=\"light dark\">\n")
          .append(styleBlock())
          .append("</head>\n<body>\n")
          .append("<table role=\"presentation\" class=\"wrapper\" width=\"100%\" cellpadding=\"0\" cellspacing=\"0\">")
          .append("<tr><td align=\"center\">\n")
          .append("<table role=\"presentation\" class=\"card\" width=\"600\" cellpadding=\"0\" cellspacing=\"0\">");

        // Header band.
        sb.append("<tr><td class=\"header\" align=\"center\" style=\"background-color:")
          .append(BRAND).append(";padding:24px;text-align:center;\">")
          .append("<span style=\"color:#ffffff;font-size:20px;font-weight:bold;\">Bhukkad</span>")
          .append("</td></tr>");

        // Body.
        sb.append("<tr><td class=\"body\" style=\"padding:28px 32px;\">")
          .append("<h1 style=\"margin:0 0 12px;color:#1c1c1e;font-size:22px;line-height:1.3;\">")
          .append(escape(heading)).append("</h1>");
        if (bodyText != null && !bodyText.isBlank()) {
            sb.append("<p style=\"margin:0 0 18px;color:#48484a;font-size:15px;line-height:1.55;\">")
              .append(escape(bodyText)).append("</p>");
        }

        if (summary != null && !summary.isEmpty()) {
            sb.append("<table role=\"presentation\" width=\"100%\" cellpadding=\"0\" cellspacing=\"0\"");
            sb.append(" class=\"summary\">");
            for (KeyValuePair pair : summary) {
                if (pair == null || pair.label() == null) {
                    continue;
                }
                sb.append("<tr>")
                  .append("<td class=\"summary-label\" style=\"padding:6px 0;color:#6b6b70;font-size:14px;\">")
                  .append(escape(pair.label())).append("</td>")
                  .append("<td class=\"summary-value\" align=\"right\" style=\"padding:6px 0;color:#1c1c1e;font-size:14px;font-weight:600;\">")
                  .append(escape(pair.value() == null ? "" : pair.value())).append("</td>")
                  .append("</tr>");
            }
            sb.append("</table>");
        }

        if (items != null && !items.isEmpty()) {
            sb.append("<ul class=\"items\" style=\"margin:16px 0 0;padding-left:20px;\">");
            for (String item : items) {
                if (item != null && !item.isBlank()) {
                    sb.append("<li style=\"color:#48484a;font-size:14px;padding:2px 0;\">")
                      .append(escape(item)).append("</li>");
                }
            }
            sb.append("</ul>");
        }

        if (ctaUrl != null && !ctaUrl.isBlank()) {
            sb.append("<p style=\"margin:26px 0 0;text-align:center;\">")
              .append("<a href=\"").append(escape(ctaUrl))
              .append("\" class=\"cta\" style=\"display:inline-block;background-color:").append(BRAND)
              .append(";color:#ffffff;text-decoration:none;font-size:15px;font-weight:bold;")
              .append("padding:13px 34px;border-radius:8px;\">")
              .append(escape(ctaLabel == null || ctaLabel.isBlank() ? "View details" : ctaLabel))
              .append("</a></p>");
        }

        sb.append("</td></tr>");

        // Footer.
        sb.append("<tr><td class=\"footer\" style=\"padding:18px 32px;border-top:1px solid #ececee;")
          .append("color:#9a9aa0;font-size:12px;text-align:center;\">")
          .append("&copy; Bhukkad \u00b7 You are receiving this email because you have a Bhukkad account.")
          .append("</td></tr>");

        sb.append("</table>\n</td></tr>\n</table>\n</body>\n</html>");
        return sb.toString();
    }

    private static String styleBlock() {
        return """
                <style>
                  body { margin:0; padding:0; background-color:#f4f4f6; }
                  .card { background-color:#ffffff; border-radius:10px; overflow:hidden;
                          max-width:600px; width:100%; margin:24px auto; font-family:-apple-system,BlinkMacSystemFont,'Segoe UI',Roboto,Helvetica,Arial,sans-serif; }
                  h1 { color:#1c1c1e; }
                  /* Dark mode: honoured by Apple Mail / iOS / Gmail Android. Inline
                     light-mode values above remain the fallback everywhere else. */
                  @media (prefers-color-scheme: dark) {
                    body { background-color:#121214 !important; }
                    .card { background-color:#1d1d20 !important; }
                    h1 { color:#f2f2f7 !important; }
                    p, li { color:#c7c7cc !important; }
                    .footer { border-top-color:#33333a !important; }
                    .summary-label { color:#98989f !important; }
                    .summary-value { color:#f2f2f7 !important; }
                  }
                  @media only screen and (max-width:620px) {
                    .card { width:100% !important; margin:0 !important; border-radius:0 !important; }
                  }
                </style>
                """;
    }

    /** Escapes text nodes conservatively — user-supplied strings must not inject HTML. */
    private static String escape(String value) {
        if (value == null) {
            return "";
        }
        return value.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;");
    }

    /** One labelled row of the summary table. */
    public record KeyValuePair(String label, String value) {
    }
}
