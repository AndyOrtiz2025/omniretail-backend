package com.omniretail.backend.purchasing.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.lowagie.text.pdf.PdfReader;
import com.lowagie.text.pdf.parser.PdfTextExtractor;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class PurchaseOrderPdfRendererTest {

    private final PurchaseOrderPdfRenderer renderer = new PurchaseOrderPdfRenderer();

    @Test
    void rendersAValidPdfWithTheOrderDetail() throws Exception {
        byte[] pdf = renderer.render(data(3, "Notas de la orden"));

        assertThat(new String(pdf, 0, 5, StandardCharsets.US_ASCII)).isEqualTo("%PDF-");
        assertThat(pdf.length).isGreaterThan(1_500);
        PdfReader reader = new PdfReader(pdf);
        try {
            String text = new PdfTextExtractor(reader).getTextFromPage(1);
            assertThat(text)
                    .contains("ORDEN DE COMPRA")
                    .contains("OC-2026-0001")
                    .contains("Producto 0")
                    .contains("Proveedor Central")
                    .contains("Notas de la orden");
        } finally {
            reader.close();
        }
    }

    @Test
    void ordersWithManyLinesProduceSeveralPagesWithoutFailing() throws Exception {
        byte[] pdf = renderer.render(data(300, null));

        PdfReader reader = new PdfReader(pdf);
        try {
            assertThat(reader.getNumberOfPages()).isGreaterThan(3);
            PdfTextExtractor extractor = new PdfTextExtractor(reader);
            StringBuilder all = new StringBuilder();
            for (int page = 1; page <= reader.getNumberOfPages(); page++) {
                all.append(extractor.getTextFromPage(page));
            }
            assertThat(all.toString()).contains("Producto 0").contains("Producto 299");
        } finally {
            reader.close();
        }
    }

    @Test
    void toleratesMissingOptionalSupplierAndBranchData() {
        PurchaseOrderPdfRenderer.Data data = new PurchaseOrderPdfRenderer.Data(
                null, "OC-MIN", "Aprobada", null, null, null,
                BigDecimal.ZERO, BigDecimal.ZERO, "Proveedor", null, null, null, null, null, null,
                List.of(new PurchaseOrderPdfRenderer.Line("Producto", "SKU", null, "un",
                        BigDecimal.ONE, BigDecimal.ONE, BigDecimal.ONE)));

        assertThat(renderer.render(data)).isNotEmpty();
    }

    private static PurchaseOrderPdfRenderer.Data data(int lines, String notes) {
        List<PurchaseOrderPdfRenderer.Line> items = new ArrayList<>();
        for (int index = 0; index < lines; index++) {
            items.add(new PurchaseOrderPdfRenderer.Line(
                    "Producto " + index, "SKU-" + index, "PROV-" + index, "cj",
                    new BigDecimal("2.000"), new BigDecimal("10.50"), new BigDecimal("21.00")));
        }
        return new PurchaseOrderPdfRenderer.Data(
                "Ferreteria Norte, S.A.", "OC-2026-0001", "Aprobada",
                Instant.parse("2026-10-05T15:00:00Z"), LocalDate.of(2026, 10, 15), notes,
                new BigDecimal("21.00").multiply(BigDecimal.valueOf(lines)),
                new BigDecimal("21.00").multiply(BigDecimal.valueOf(lines)),
                "Proveedor Central", "Proveedor Central, S.A.", "1234567-8", "ventas@proveedor.com", "5555-1234",
                "Sucursal Central", "Zona 10", items);
    }
}
