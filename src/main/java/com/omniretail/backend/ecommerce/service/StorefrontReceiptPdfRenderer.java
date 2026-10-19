package com.omniretail.backend.ecommerce.service;

import com.lowagie.text.Document;
import com.lowagie.text.Element;
import com.lowagie.text.Font;
import com.lowagie.text.PageSize;
import com.lowagie.text.Paragraph;
import com.lowagie.text.Phrase;
import com.lowagie.text.pdf.PdfPCell;
import com.lowagie.text.pdf.PdfPTable;
import com.lowagie.text.pdf.PdfWriter;
import com.omniretail.backend.ecommerce.entity.Order;
import com.omniretail.backend.ecommerce.entity.OrderItem;
import java.awt.Color;
import java.io.ByteArrayOutputStream;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.text.DecimalFormat;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

/** Documento único del pedido: se adjunta al correo y se ofrece para descargar. */
@Component
public class StorefrontReceiptPdfRenderer {

    private static final Color NAVY = new Color(22, 74, 135);
    private static final Color BLUE = new Color(14, 142, 205);
    private static final Color INK = new Color(20, 36, 58);
    private static final Color PALE = new Color(244, 247, 251);
    private static final Color BORDER = new Color(205, 215, 228);
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("dd/MM/yyyy")
            .withZone(ZoneId.of("America/Guatemala"));

    public byte[] render(String storeName, Order order, List<OrderItem> items, Map<String, Object> address) {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        Document document = new Document(PageSize.LETTER, 42, 42, 36, 42);
        try {
            PdfWriter.getInstance(document, output);
            document.open();
            document.add(header(storeName, order));
            document.add(space(10));
            document.add(info(order, address));
            document.add(space(12));
            document.add(lines(items));
            document.add(space(12));
            document.add(totals(order));
            document.add(space(16));
            Paragraph footer = new Paragraph("Comprobante de pedido · No es una factura fiscal", font(8, Font.NORMAL, new Color(91, 108, 130)));
            footer.setAlignment(Element.ALIGN_CENTER);
            document.add(footer);
            document.close();
        } catch (Exception exception) {
            throw new IllegalStateException("No se pudo generar el comprobante del pedido.", exception);
        }
        return output.toByteArray();
    }

    private PdfPTable header(String storeName, Order order) {
        PdfPTable table = new PdfPTable(new float[] {2.4f, 1.4f});
        table.setWidthPercentage(100);
        table.addCell(box((storeName == null ? "Tienda" : storeName).toUpperCase() + "\nCOMPROBANTE DE PEDIDO\nPedido: " + order.getOrderNumber(), NAVY, Color.WHITE, 14, Element.ALIGN_LEFT));
        table.addCell(box("Fecha: " + DATE.format(order.getCreatedAt()) + "\nEstado: " + (order.getStatus().name().equals("confirmed") ? "Pago confirmado" : "Pago pendiente") + "\n" + money(order.getTotal()), NAVY, Color.WHITE, 14, Element.ALIGN_RIGHT));
        return table;
    }

    private PdfPTable info(Order order, Map<String, Object> address) {
        PdfPTable table = new PdfPTable(2);
        table.setWidthPercentage(100);
        String delivery = value(address, "recipientName") + "\n" + value(address, "line1")
                + "\n" + value(address, "city") + (blank(value(address, "stateOrDepartment")) ? "" : ", " + value(address, "stateOrDepartment"));
        table.addCell(box("ENTREGA A\n" + delivery, PALE, INK, 10, Element.ALIGN_LEFT));
        table.addCell(box("MÉTODO DE PAGO\nTarjeta de crédito o débito\nInformación de pago confirmada", PALE, INK, 10, Element.ALIGN_LEFT));
        return table;
    }

    private PdfPTable lines(List<OrderItem> items) {
        PdfPTable table = new PdfPTable(new float[] {3.6f, 1f, 1.3f, 1.4f});
        table.setWidthPercentage(100);
        table.setHeaderRows(1);
        String[] labels = {"DESCRIPCIÓN", "CANT.", "P. UNIT.", "IMPORTE"};
        for (int index = 0; index < labels.length; index++) table.addCell(box(labels[index], BLUE, Color.WHITE, 6, index == 0 ? Element.ALIGN_LEFT : Element.ALIGN_RIGHT));
        for (OrderItem item : items) {
            table.addCell(box(item.getNameSnapshot() + "\nSKU: " + item.getSkuSnapshot(), Color.WHITE, INK, 6, Element.ALIGN_LEFT));
            table.addCell(box(number(item.getQuantity()), Color.WHITE, INK, 6, Element.ALIGN_RIGHT));
            table.addCell(box(money(item.getUnitPrice()), Color.WHITE, INK, 6, Element.ALIGN_RIGHT));
            table.addCell(box(money(item.getSubtotal()), Color.WHITE, INK, 6, Element.ALIGN_RIGHT));
        }
        return table;
    }

    private PdfPTable totals(Order order) {
        PdfPTable table = new PdfPTable(new float[] {1.2f, 1f});
        table.setWidthPercentage(42);
        table.setHorizontalAlignment(Element.ALIGN_RIGHT);
        row(table, "Subtotal", money(order.getSubtotal()), false);
        row(table, "Envío", money(order.getShippingTotal()), false);
        row(table, "TOTAL", money(order.getTotal()), true);
        return table;
    }

    private void row(PdfPTable table, String label, String amount, boolean strong) {
        int style = strong ? Font.BOLD : Font.NORMAL;
        table.addCell(box(label, PALE, INK, 6, Element.ALIGN_LEFT, style));
        table.addCell(box(amount, PALE, INK, 6, Element.ALIGN_RIGHT, style));
    }

    private PdfPCell box(String text, Color background, Color color, float padding, int alignment) { return box(text, background, color, padding, alignment, Font.NORMAL); }
    private PdfPCell box(String text, Color background, Color color, float padding, int alignment, int style) {
        PdfPCell cell = new PdfPCell(new Phrase(text, font(8.5f, style, color)));
        cell.setBackgroundColor(background); cell.setBorderColor(BORDER); cell.setPadding(padding); cell.setHorizontalAlignment(alignment); return cell;
    }
    private static Paragraph space(float leading) { Paragraph p = new Paragraph(" ", font(1, Font.NORMAL, Color.WHITE)); p.setLeading(leading); return p; }
    private static Font font(float size, int style, Color color) { return new Font(Font.HELVETICA, size, style, color); }
    private static String money(BigDecimal value) { return "Q" + new DecimalFormat("#,##0.00").format((value == null ? BigDecimal.ZERO : value).setScale(2, RoundingMode.HALF_UP)); }
    private static String number(BigDecimal value) { return value == null ? "-" : value.stripTrailingZeros().toPlainString(); }
    private static String value(Map<String, Object> values, String key) { Object value = values == null ? null : values.get(key); return value == null ? "-" : String.valueOf(value); }
    private static boolean blank(String value) { return value == null || value.isBlank() || "-".equals(value); }
}
