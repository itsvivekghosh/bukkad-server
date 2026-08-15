package com.bhukkad.invoice;

import com.bhukkad.entity.Address;
import com.bhukkad.entity.Order;
import com.bhukkad.entity.OrderInvoice;
import com.bhukkad.entity.OrderItem;
import com.bhukkad.entity.Restaurant;
import com.lowagie.text.Document;
import com.lowagie.text.Element;
import com.lowagie.text.Font;
import com.lowagie.text.FontFactory;
import com.lowagie.text.PageSize;
import com.lowagie.text.Paragraph;
import com.lowagie.text.Phrase;
import com.lowagie.text.pdf.PdfPCell;
import com.lowagie.text.pdf.PdfPTable;
import com.lowagie.text.pdf.PdfWriter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.awt.Color;
import java.io.ByteArrayOutputStream;
import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * Renders a GST-compliant invoice PDF for a delivered order using OpenPDF.
 *
 * <p>The layout follows the minimum requirements of an Indian tax invoice for a
 * B2C food-delivery transaction:</p>
 * <ul>
 *   <li>Supplier (restaurant) name, address and GSTIN</li>
 *   <li>Invoice number and issue date</li>
 *   <li>Recipient name and delivery address (place of supply)</li>
 *   <li>Line items with quantity, unit price and line total</li>
 *   <li>Taxable value, CGST and SGST shown separately, then the grand total</li>
 * </ul>
 *
 * <p>Because both the restaurant and the customer are in the same state for a
 * delivery order, the tax split is always CGST + SGST (never IGST). The split is
 * taken from the persisted invoice rather than recomputed, so the PDF can never
 * disagree with the stored invoice record.</p>
 *
 * <p>This component is stateless and produces the PDF fully in memory; invoices
 * are single-page documents, so the byte array stays small.</p>
 */
@Slf4j
@Component
public class InvoicePdfRenderer {

    private static final DateTimeFormatter DATE_FORMAT =
            DateTimeFormatter.ofPattern("dd MMM yyyy, HH:mm");

    private static final Font TITLE_FONT = FontFactory.getFont(FontFactory.HELVETICA_BOLD, 18, Color.BLACK);
    private static final Font HEADING_FONT = FontFactory.getFont(FontFactory.HELVETICA_BOLD, 11, Color.BLACK);
    private static final Font BODY_FONT = FontFactory.getFont(FontFactory.HELVETICA, 10, Color.BLACK);
    private static final Font SMALL_FONT = FontFactory.getFont(FontFactory.HELVETICA, 8, Color.DARK_GRAY);
    private static final Font TOTAL_FONT = FontFactory.getFont(FontFactory.HELVETICA_BOLD, 12, Color.BLACK);

    /** Rupee amounts are rendered with the ISO code because the glyph is not in Helvetica. */
    private static final String CURRENCY = "INR ";

    /**
     * Renders the invoice to PDF bytes.
     *
     * @param invoice persisted invoice holding the authoritative amounts
     * @return the rendered PDF document as a byte array
     * @throws IllegalStateException if the PDF cannot be produced
     */
    public byte[] render(OrderInvoice invoice) {
        Order order = invoice.getOrder();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        Document document = new Document(PageSize.A4, 36, 36, 36, 36);

        try {
            PdfWriter.getInstance(document, out);
            document.addTitle("Tax Invoice " + invoice.getInvoiceNumber());
            document.open();

            document.add(header(invoice, order));
            document.add(supplierAndRecipient(invoice, order));
            document.add(itemsTable(order));
            document.add(totalsTable(invoice));
            document.add(footer());

            document.close();
            return out.toByteArray();
        } catch (Exception ex) {
            // Rendering failures must not be swallowed silently: the caller
            // decides whether to fall back to the JSON invoice.
            log.error("INVOICE_PDF_RENDER_FAILED | invoice={} | error={}",
                    invoice.getInvoiceNumber(), ex.getMessage(), ex);
            throw new IllegalStateException("Unable to render invoice PDF", ex);
        } finally {
            if (document.isOpen()) {
                document.close();
            }
        }
    }

    private Element header(OrderInvoice invoice, Order order) {
        PdfPTable table = new PdfPTable(2);
        table.setWidthPercentage(100);
        table.setSpacingAfter(14f);

        Paragraph title = new Paragraph("TAX INVOICE", TITLE_FONT);
        PdfPCell titleCell = borderless(title);
        titleCell.setHorizontalAlignment(Element.ALIGN_LEFT);
        table.addCell(titleCell);

        Paragraph meta = new Paragraph();
        meta.setFont(BODY_FONT);
        meta.add("Invoice No: " + safe(invoice.getInvoiceNumber()) + "\n");
        meta.add("Date: " + (invoice.getIssuedAt() != null ? invoice.getIssuedAt().format(DATE_FORMAT) : "-") + "\n");
        meta.add("Order No: " + (order != null ? safe(order.getOrderNumber()) : "-"));
        PdfPCell metaCell = borderless(meta);
        metaCell.setHorizontalAlignment(Element.ALIGN_RIGHT);
        table.addCell(metaCell);

        return table;
    }

    private Element supplierAndRecipient(OrderInvoice invoice, Order order) {
        PdfPTable table = new PdfPTable(2);
        table.setWidthPercentage(100);
        table.setSpacingAfter(14f);

        Restaurant restaurant = order != null ? order.getRestaurant() : null;
        Paragraph supplier = new Paragraph();
        supplier.add(new Phrase("Sold By\n", HEADING_FONT));
        supplier.add(new Phrase(restaurant != null ? safe(restaurant.getName()) + "\n" : "-\n", BODY_FONT));
        if (restaurant != null && restaurant.getAddress() != null) {
            supplier.add(new Phrase(formatAddress(restaurant.getAddress()) + "\n", BODY_FONT));
        }
        supplier.add(new Phrase("GSTIN: " + orDash(invoice.getRestaurantGstin()), BODY_FONT));
        table.addCell(borderless(supplier));

        Paragraph recipient = new Paragraph();
        recipient.add(new Phrase("Delivered To\n", HEADING_FONT));
        String customerName = order != null && order.getCustomer() != null
                ? safe(order.getCustomer().getFullName())
                : "-";
        recipient.add(new Phrase(customerName + "\n", BODY_FONT));
        if (order != null && order.getDeliveryAddress() != null) {
            Address address = order.getDeliveryAddress();
            recipient.add(new Phrase(formatAddress(address) + "\n", BODY_FONT));
            recipient.add(new Phrase("Place of Supply: " + orDash(address.getState()), BODY_FONT));
        }
        table.addCell(borderless(recipient));

        return table;
    }

    private Element itemsTable(Order order) {
        PdfPTable table = new PdfPTable(new float[]{6f, 1.5f, 2f, 2f});
        table.setWidthPercentage(100);
        table.setSpacingAfter(10f);

        table.addCell(headerCell("Item"));
        table.addCell(headerCell("Qty"));
        table.addCell(headerCell("Unit Price"));
        table.addCell(headerCell("Amount"));

        List<OrderItem> items = order != null && order.getOrderItems() != null
                ? order.getOrderItems()
                : List.of();

        if (items.isEmpty()) {
            PdfPCell empty = new PdfPCell(new Phrase("No line items recorded", BODY_FONT));
            empty.setColspan(4);
            empty.setPadding(6f);
            table.addCell(empty);
            return table;
        }

        for (OrderItem item : items) {
            int quantity = item.getQuantity() != null ? item.getQuantity() : 0;
            double unitPrice = item.getPrice() != null ? item.getPrice() : 0.0;
            String name = item.getMenuItem() != null ? safe(item.getMenuItem().getName()) : "Item";

            table.addCell(bodyCell(name, Element.ALIGN_LEFT));
            table.addCell(bodyCell(String.valueOf(quantity), Element.ALIGN_CENTER));
            table.addCell(bodyCell(money(unitPrice), Element.ALIGN_RIGHT));
            table.addCell(bodyCell(money(unitPrice * quantity), Element.ALIGN_RIGHT));
        }

        return table;
    }

    private Element totalsTable(OrderInvoice invoice) {
        PdfPTable table = new PdfPTable(new float[]{7f, 3f});
        table.setWidthPercentage(100);
        table.setSpacingAfter(16f);

        addTotalRow(table, "Taxable Value", money(nz(invoice.getSubtotal())), BODY_FONT);
        addTotalRow(table, "Delivery Fee", money(nz(invoice.getDeliveryFee())), BODY_FONT);
        if (nz(invoice.getDiscountAmount()) > 0) {
            addTotalRow(table, "Discount", "- " + money(nz(invoice.getDiscountAmount())), BODY_FONT);
        }
        addTotalRow(table, "CGST", money(nz(invoice.getCgstAmount())), BODY_FONT);
        addTotalRow(table, "SGST", money(nz(invoice.getSgstAmount())), BODY_FONT);
        addTotalRow(table, "Total Payable", money(nz(invoice.getTotalAmount())), TOTAL_FONT);

        return table;
    }

    private Element footer() {
        Paragraph footer = new Paragraph(
                "This is a computer-generated invoice issued on behalf of the restaurant partner. "
                        + "Bhukkad acts as an intermediary marketplace and is not the supplier of the food items listed above.",
                SMALL_FONT);
        footer.setSpacingBefore(8f);
        return footer;
    }

    private void addTotalRow(PdfPTable table, String label, String value, Font font) {
        PdfPCell labelCell = new PdfPCell(new Phrase(label, font));
        labelCell.setHorizontalAlignment(Element.ALIGN_RIGHT);
        labelCell.setBorder(com.lowagie.text.Rectangle.TOP);
        labelCell.setBorderColor(Color.LIGHT_GRAY);
        labelCell.setPadding(5f);
        table.addCell(labelCell);

        PdfPCell valueCell = new PdfPCell(new Phrase(value, font));
        valueCell.setHorizontalAlignment(Element.ALIGN_RIGHT);
        valueCell.setBorder(com.lowagie.text.Rectangle.TOP);
        valueCell.setBorderColor(Color.LIGHT_GRAY);
        valueCell.setPadding(5f);
        table.addCell(valueCell);
    }

    private PdfPCell headerCell(String text) {
        PdfPCell cell = new PdfPCell(new Phrase(text, HEADING_FONT));
        cell.setBackgroundColor(new Color(240, 240, 240));
        cell.setPadding(6f);
        return cell;
    }

    private PdfPCell bodyCell(String text, int alignment) {
        PdfPCell cell = new PdfPCell(new Phrase(text, BODY_FONT));
        cell.setHorizontalAlignment(alignment);
        cell.setPadding(5f);
        return cell;
    }

    private PdfPCell borderless(Element content) {
        PdfPCell cell = new PdfPCell();
        cell.addElement(content);
        cell.setBorder(com.lowagie.text.Rectangle.NO_BORDER);
        return cell;
    }

    private String formatAddress(Address address) {
        StringBuilder sb = new StringBuilder();
        appendIfPresent(sb, address.getAddressLine1());
        appendIfPresent(sb, address.getAddressLine2());
        appendIfPresent(sb, address.getCity());
        appendIfPresent(sb, address.getState());
        appendIfPresent(sb, address.getPincode());
        return sb.length() == 0 ? "-" : sb.toString();
    }

    private void appendIfPresent(StringBuilder sb, String value) {
        if (value != null && !value.isBlank()) {
            if (sb.length() > 0) {
                sb.append(", ");
            }
            sb.append(value.trim());
        }
    }

    private String money(double amount) {
        return CURRENCY + String.format("%.2f", amount);
    }

    private double nz(Double value) {
        return value != null ? value : 0.0;
    }

    private String safe(String value) {
        return value != null ? value : "-";
    }

    private String orDash(String value) {
        return value != null && !value.isBlank() ? value : "Not registered";
    }
}
