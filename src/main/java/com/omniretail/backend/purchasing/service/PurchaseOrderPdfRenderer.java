package com.omniretail.backend.purchasing.service;

import com.lowagie.text.Document;
import com.lowagie.text.DocumentException;
import com.lowagie.text.Element;
import com.lowagie.text.Font;
import com.lowagie.text.PageSize;
import com.lowagie.text.Paragraph;
import com.lowagie.text.Phrase;
import com.lowagie.text.Rectangle;
import com.lowagie.text.pdf.BaseFont;
import com.lowagie.text.pdf.ColumnText;
import com.lowagie.text.pdf.PdfContentByte;
import com.lowagie.text.pdf.PdfPCell;
import com.lowagie.text.pdf.PdfPTable;
import com.lowagie.text.pdf.PdfPageEventHelper;
import com.lowagie.text.pdf.PdfTemplate;
import com.lowagie.text.pdf.PdfWriter;
import java.awt.Color;
import java.io.ByteArrayOutputStream;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import org.springframework.stereotype.Component;

/**
 * Genera en memoria el PDF de una orden de compra (carta, varias páginas). El diseño sigue el PDF del frontend
 * (encabezado navy, paneles proveedor/entrega, tabla de productos, resumen y notas) sin buscar igualdad exacta.
 * No consulta la base de datos: recibe los datos ya cargados, por lo que es trivial de probar.
 */
@Component
public class PurchaseOrderPdfRenderer {

    public record Line(
            String productName,
            String sku,
            String supplierSku,
            String unit,
            BigDecimal quantity,
            BigDecimal unitCost,
            BigDecimal subtotal) {}

    public record Data(
            String businessName,
            String orderNumber,
            String statusLabel,
            Instant orderDate,
            LocalDate expectedDate,
            String notes,
            BigDecimal subtotal,
            BigDecimal total,
            String supplierName,
            String supplierLegalName,
            String supplierTaxId,
            String supplierEmail,
            String supplierPhone,
            String branchName,
            String branchAddress,
            List<Line> lines) {}

    private static final Color NAVY = new Color(20, 49, 88);
    private static final Color BLUE = new Color(46, 105, 160);
    private static final Color INK = new Color(33, 37, 41);
    private static final Color MUTED = new Color(95, 108, 125);
    private static final Color PALE = new Color(241, 245, 250);
    private static final Color BORDER = new Color(211, 220, 235);
    private static final ZoneId ZONE = ZoneId.of("America/Guatemala");
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("dd/MM/yyyy");
    private static final DateTimeFormatter DATE_TIME = DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm");

    public byte[] render(Data data) {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        Document document = new Document(PageSize.LETTER, 40, 40, 36, 52);
        try {
            PdfWriter writer = PdfWriter.getInstance(document, output);
            writer.setPageEvent(new FooterEvent());
            document.addTitle("Orden de compra " + data.orderNumber());
            document.addCreator("OmniRetail");
            document.open();
            document.add(header(data));
            document.add(spacer(10));
            document.add(panels(data));
            document.add(spacer(12));
            document.add(sectionHeading("PRODUCTOS"));
            document.add(linesTable(data));
            document.add(spacer(10));
            document.add(summary(data));
            if (data.notes() != null && !data.notes().isBlank()) {
                document.add(spacer(10));
                document.add(sectionHeading("NOTAS"));
                document.add(new Paragraph(data.notes().trim(), font(8.5f, Font.NORMAL, INK)));
            }
            document.close();
        } catch (DocumentException exception) {
            throw new IllegalStateException("No se pudo generar el PDF de la orden de compra.", exception);
        }
        return output.toByteArray();
    }

    private PdfPTable header(Data data) throws DocumentException {
        PdfPTable table = new PdfPTable(new float[] {3f, 1.4f});
        table.setWidthPercentage(100);
        PdfPCell left = cell(NAVY, 14);
        left.addElement(new Paragraph(text(data.businessName()).toUpperCase(Locale.ROOT), font(8f, Font.BOLD, Color.WHITE)));
        Paragraph title = new Paragraph("ORDEN DE COMPRA", font(18f, Font.BOLD, Color.WHITE));
        title.setSpacingBefore(4);
        left.addElement(title);
        Paragraph subtitle = new Paragraph(
                "Orden " + data.orderNumber() + " · " + text(data.statusLabel()), font(9f, Font.NORMAL, Color.WHITE));
        subtitle.setSpacingBefore(3);
        left.addElement(subtitle);
        PdfPCell right = cell(NAVY, 14);
        right.setHorizontalAlignment(Element.ALIGN_RIGHT);
        Paragraph date = new Paragraph(
                DATE_TIME.format(java.time.ZonedDateTime.now(ZONE)), font(8f, Font.NORMAL, Color.WHITE));
        date.setAlignment(Element.ALIGN_RIGHT);
        right.addElement(date);
        Paragraph total = new Paragraph(money(data.total()), font(15f, Font.BOLD, Color.WHITE));
        total.setAlignment(Element.ALIGN_RIGHT);
        total.setSpacingBefore(8);
        right.addElement(total);
        table.addCell(left);
        table.addCell(right);
        return table;
    }

    private PdfPTable panels(Data data) throws DocumentException {
        PdfPTable table = new PdfPTable(new float[] {1f, 0.06f, 1f});
        table.setWidthPercentage(100);
        table.addCell(panel("PROVEEDOR", new String[][] {
                {"Proveedor", data.supplierName()},
                {"Razon social", data.supplierLegalName()},
                {"NIT", data.supplierTaxId()},
                {"Correo", data.supplierEmail()},
                {"Contacto", data.supplierPhone()}}));
        PdfPCell gap = new PdfPCell(new Phrase(""));
        gap.setBorder(Rectangle.NO_BORDER);
        table.addCell(gap);
        table.addCell(panel("ENTREGA", new String[][] {
                {"Sucursal destino", data.branchName()},
                {"Direccion", data.branchAddress()},
                {"Fecha esperada", data.expectedDate() == null ? "-" : DATE.format(data.expectedDate())},
                {"Fecha de orden", data.orderDate() == null ? "-" : DATE.format(data.orderDate().atZone(ZONE))}}));
        return table;
    }

    private PdfPCell panel(String title, String[][] entries) {
        PdfPCell cell = new PdfPCell();
        cell.setBorderColor(BORDER);
        cell.setBackgroundColor(new Color(252, 251, 247));
        cell.setPadding(8);
        cell.addElement(new Paragraph(title, font(8.5f, Font.BOLD, NAVY)));
        for (String[] entry : entries) {
            Paragraph label = new Paragraph(entry[0].toUpperCase(Locale.ROOT), font(6.5f, Font.NORMAL, MUTED));
            label.setSpacingBefore(5);
            cell.addElement(label);
            cell.addElement(new Paragraph(text(entry[1]), font(8.5f, Font.NORMAL, INK)));
        }
        return cell;
    }

    private Paragraph sectionHeading(String title) {
        Paragraph heading = new Paragraph(title, font(10f, Font.BOLD, NAVY));
        heading.setSpacingAfter(4);
        return heading;
    }

    private PdfPTable linesTable(Data data) throws DocumentException {
        PdfPTable table = new PdfPTable(new float[] {3.2f, 2.4f, 1.1f, 1.2f, 1.5f, 1.6f});
        table.setWidthPercentage(100);
        table.setHeaderRows(1);
        String[] labels = {"Producto", "SKU / proveedor", "Unidad", "Cantidad", "Costo unit.", "Subtotal"};
        for (int index = 0; index < labels.length; index++) {
            PdfPCell cell = new PdfPCell(new Phrase(labels[index].toUpperCase(Locale.ROOT), font(6.8f, Font.BOLD, Color.WHITE)));
            cell.setBackgroundColor(NAVY);
            cell.setBorderColor(NAVY);
            cell.setPadding(5);
            cell.setHorizontalAlignment(index >= 3 ? Element.ALIGN_RIGHT : Element.ALIGN_LEFT);
            table.addCell(cell);
        }
        for (Line line : data.lines()) {
            table.addCell(bodyCell(line.productName(), false));
            table.addCell(bodyCell(text(line.sku()) + " / " + text(line.supplierSku()), false));
            table.addCell(bodyCell(line.unit(), false));
            table.addCell(bodyCell(number(line.quantity()), true));
            table.addCell(bodyCell(money(line.unitCost()), true));
            table.addCell(bodyCell(money(line.subtotal()), true));
        }
        return table;
    }

    private PdfPCell bodyCell(String value, boolean right) {
        PdfPCell cell = new PdfPCell(new Phrase(text(value), font(7.5f, Font.NORMAL, INK)));
        cell.setBorderColor(BORDER);
        cell.setPadding(4);
        cell.setHorizontalAlignment(right ? Element.ALIGN_RIGHT : Element.ALIGN_LEFT);
        return cell;
    }

    private PdfPTable summary(Data data) throws DocumentException {
        PdfPTable table = new PdfPTable(new float[] {1.4f, 1f});
        table.setWidthPercentage(45);
        table.setHorizontalAlignment(Element.ALIGN_RIGHT);
        table.setKeepTogether(true);
        BigDecimal discount = data.subtotal() == null || data.total() == null
                ? BigDecimal.ZERO
                : data.subtotal().subtract(data.total()).max(BigDecimal.ZERO);
        summaryRow(table, "Subtotal", money(data.subtotal()), false);
        summaryRow(table, "Descuentos / ahorros", discount.signum() > 0 ? money(discount) : "-", false);
        summaryRow(table, "TOTAL FINAL", money(data.total()), true);
        return table;
    }

    private void summaryRow(PdfPTable table, String label, String value, boolean strong) {
        int style = strong ? Font.BOLD : Font.NORMAL;
        PdfPCell left = new PdfPCell(new Phrase(label, font(8f, style, INK)));
        PdfPCell right = new PdfPCell(new Phrase(value, font(8f, style, INK)));
        right.setHorizontalAlignment(Element.ALIGN_RIGHT);
        for (PdfPCell cell : new PdfPCell[] {left, right}) {
            cell.setBackgroundColor(PALE);
            cell.setBorderColor(BORDER);
            cell.setPadding(5);
            table.addCell(cell);
        }
    }

    private PdfPCell cell(Color background, float padding) {
        PdfPCell cell = new PdfPCell();
        cell.setBackgroundColor(background);
        cell.setBorderColor(background);
        cell.setPadding(padding);
        return cell;
    }

    private Paragraph spacer(float height) {
        Paragraph paragraph = new Paragraph(" ", font(1f, Font.NORMAL, Color.WHITE));
        paragraph.setLeading(height);
        return paragraph;
    }

    private static Font font(float size, int style, Color color) {
        return new Font(Font.HELVETICA, size, style, color);
    }

    private static String text(String value) {
        return value == null || value.isBlank() ? "-" : value.trim();
    }

    private static String number(BigDecimal value) {
        if (value == null) {
            return "-";
        }
        DecimalFormat format = new DecimalFormat("#,##0.###", DecimalFormatSymbols.getInstance(Locale.US));
        return format.format(value);
    }

    private static String money(BigDecimal value) {
        BigDecimal amount = value == null ? BigDecimal.ZERO : value.setScale(2, RoundingMode.HALF_UP);
        DecimalFormat format = new DecimalFormat("#,##0.00", DecimalFormatSymbols.getInstance(Locale.US));
        return "Q" + format.format(amount);
    }

    /** Pie en todas las páginas: texto de generación y "Página X / Y" (el total se rellena al cerrar). */
    private static final class FooterEvent extends PdfPageEventHelper {

        private PdfTemplate totalPages;
        private BaseFont baseFont;

        @Override
        public void onOpenDocument(PdfWriter writer, Document document) {
            try {
                baseFont = BaseFont.createFont(BaseFont.HELVETICA, BaseFont.WINANSI, false);
            } catch (Exception exception) {
                throw new IllegalStateException(exception);
            }
            totalPages = writer.getDirectContent().createTemplate(30, 12);
        }

        @Override
        public void onEndPage(PdfWriter writer, Document document) {
            PdfContentByte canvas = writer.getDirectContent();
            float y = document.bottom() - 22;
            canvas.setColorStroke(BORDER);
            canvas.moveTo(document.left(), y + 12);
            canvas.lineTo(document.right(), y + 12);
            canvas.stroke();
            ColumnText.showTextAligned(
                    canvas,
                    Element.ALIGN_LEFT,
                    new Phrase("Documento generado por OmniRetail", font(7f, Font.NORMAL, MUTED)),
                    document.left(),
                    y,
                    0);
            String label = "Pagina " + writer.getPageNumber() + " / ";
            float width = baseFont.getWidthPoint(label, 7f);
            float x = document.right() - width - 12;
            canvas.beginText();
            canvas.setFontAndSize(baseFont, 7f);
            canvas.setColorFill(MUTED);
            canvas.setTextMatrix(x, y);
            canvas.showText(label);
            canvas.endText();
            canvas.addTemplate(totalPages, x + width, y);
        }

        @Override
        public void onCloseDocument(PdfWriter writer, Document document) {
            totalPages.beginText();
            totalPages.setFontAndSize(baseFont, 7f);
            totalPages.setColorFill(MUTED);
            totalPages.setTextMatrix(0, 0);
            totalPages.showText(String.valueOf(writer.getPageNumber() - 1));
            totalPages.endText();
        }
    }
}
