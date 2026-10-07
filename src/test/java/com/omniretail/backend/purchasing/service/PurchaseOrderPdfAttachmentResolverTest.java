package com.omniretail.backend.purchasing.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.lowagie.text.pdf.PdfReader;
import com.lowagie.text.pdf.parser.PdfTextExtractor;
import com.omniretail.backend.TestcontainersConfiguration;
import com.omniretail.backend.shared.notification.EmailAttachmentContent;
import com.omniretail.backend.shared.notification.EmailAttachmentReference;
import com.omniretail.backend.shared.notification.EmailDeliveryException;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest
@ActiveProfiles("test")
@Import(TestcontainersConfiguration.class)
class PurchaseOrderPdfAttachmentResolverTest {

    @Autowired private PurchaseOrderPdfAttachmentResolver resolver;
    @Autowired private JdbcTemplate jdbc;

    @Test
    void generatesThePdfFromAuthoritativeDataOfTheOrder() throws Exception {
        Fixture fixture = fixture("Ferreteria Norte");

        EmailAttachmentContent content = resolver.resolve(fixture.tenant(), reference(fixture.order()));

        assertThat(content.filename()).isEqualTo("OC-TEST-orden-compra.pdf");
        assertThat(content.mediaType()).isEqualTo("application/pdf");
        assertThat(new String(content.bytes(), 0, 5, StandardCharsets.US_ASCII)).isEqualTo("%PDF-");
        PdfReader reader = new PdfReader(content.bytes());
        try {
            String text = new PdfTextExtractor(reader).getTextFromPage(1);
            assertThat(text)
                    .contains("FERRETERIA NORTE")
                    .contains("OC-TEST")
                    .contains("Proveedor Demo")
                    .contains("Producto uno")
                    .contains("Producto dos")
                    .contains("Sucursal Principal")
                    .contains("Aprobada");
        } finally {
            reader.close();
        }
    }

    @Test
    void neverResolvesAnOrderOfAnotherTenantOrAnUnknownOne() {
        Fixture owner = fixture("Negocio A");
        Fixture other = fixture("Negocio B");

        assertThat(resolver.supports(PurchaseOrderPdfAttachmentResolver.TYPE)).isTrue();
        assertThat(resolver.supports("OTHER")).isFalse();
        assertThatThrownBy(() -> resolver.resolve(other.tenant(), reference(owner.order())))
                .isInstanceOfSatisfying(EmailDeliveryException.class, exception ->
                        assertThat(exception.getCode()).isEqualTo(EmailDeliveryException.ATTACHMENT_UNAVAILABLE));
        assertThatThrownBy(() -> resolver.resolve(owner.tenant(), reference(UUID.randomUUID())))
                .isInstanceOfSatisfying(EmailDeliveryException.class, exception ->
                        assertThat(exception.getCode()).isEqualTo(EmailDeliveryException.ATTACHMENT_UNAVAILABLE));
    }

    @Test
    void regeneratingTheSameApprovedOrderKeepsTheSameCommercialContent() throws Exception {
        Fixture fixture = fixture("Negocio Estable");

        String first = text(resolver.resolve(fixture.tenant(), reference(fixture.order())).bytes());
        String second = text(resolver.resolve(fixture.tenant(), reference(fixture.order())).bytes());

        // El pie y el encabezado llevan la hora de generación; el contenido comercial es el mismo.
        assertThat(second).contains("Producto uno").contains("Producto dos");
        assertThat(first).contains("Producto uno").contains("Producto dos");
    }

    private static String text(byte[] pdf) throws Exception {
        PdfReader reader = new PdfReader(pdf);
        try {
            return new PdfTextExtractor(reader).getTextFromPage(1);
        } finally {
            reader.close();
        }
    }

    private static EmailAttachmentReference reference(UUID order) {
        return new EmailAttachmentReference(
                PurchaseOrderPdfAttachmentResolver.TYPE, order, "OC-TEST-orden-compra.pdf", "application/pdf");
    }

    private Fixture fixture(String tenantName) {
        UUID tenant = UUID.randomUUID();
        UUID branch = UUID.randomUUID();
        UUID user = UUID.randomUUID();
        UUID category = UUID.randomUUID();
        UUID unit = UUID.randomUUID();
        UUID supplier = UUID.randomUUID();
        UUID order = UUID.randomUUID();
        String suffix = tenant.toString();
        jdbc.update("INSERT INTO tenants (id, name, slug) VALUES (?, ?, ?)", tenant, tenantName, "pdf-" + suffix);
        jdbc.update("""
                INSERT INTO branches (id, tenant_id, code, name, type, status, address)
                VALUES (?, ?, ?, 'Sucursal Principal', 'main', 'active', 'Zona 10')
                """, branch, tenant, "B-" + suffix.substring(0, 8));
        jdbc.update("""
                INSERT INTO users (id, tenant_id, name, email, type, status, branch_id)
                VALUES (?, ?, 'Comprador', ?, 'employee', 'active', ?)
                """, user, tenant, user + "@test.local", branch);
        jdbc.update("INSERT INTO categories (id, tenant_id, name, slug) VALUES (?, ?, 'Cat', ?)",
                category, tenant, "cat-" + suffix);
        jdbc.update("""
                INSERT INTO units (id, tenant_id, code, name, symbol, category, allows_decimals, status)
                VALUES (?, ?, ?, 'Unidad', 'u', 'unit', true, 'active')
                """, unit, tenant, "U-" + suffix.substring(0, 8));
        jdbc.update("""
                INSERT INTO suppliers (id, tenant_id, name, legal_name, tax_id, email, phone, status)
                VALUES (?, ?, 'Proveedor Demo', 'Proveedor Demo, S.A.', '1234567-8', 'ventas@demo.com', '5555-1234', 'active')
                """, supplier, tenant);
        jdbc.update("""
                INSERT INTO purchase_orders
                    (id, tenant_id, branch_id, number, supplier_id, supplier_name_snapshot, status,
                     subtotal, total, created_by_user_id, notes, expected_date)
                VALUES (?, ?, ?, 'OC-TEST', ?, 'Proveedor Demo', 'approved', 30, 30, ?, 'Nota de prueba', '2030-01-15')
                """, order, tenant, branch, supplier, user);
        for (String[] line : new String[][] {{"Producto uno", "SKU-1", "10"}, {"Producto dos", "SKU-2", "20"}}) {
            UUID product = UUID.randomUUID();
            UUID supplierProduct = UUID.randomUUID();
            jdbc.update("""
                    INSERT INTO products (id, tenant_id, sku, name, category_id, base_unit_id)
                    VALUES (?, ?, ?, ?, ?, ?)
                    """, product, tenant, line[1] + "-" + product, line[0], category, unit);
            jdbc.update("""
                    INSERT INTO supplier_products
                        (id, tenant_id, supplier_id, product_id, purchase_unit_id, purchase_to_base_factor,
                         last_cost, lead_time_days, minimum_order_quantity)
                    VALUES (?, ?, ?, ?, ?, 1, 1, 0, 1)
                    """, supplierProduct, tenant, supplier, product, unit);
            jdbc.update("""
                    INSERT INTO purchase_order_items
                        (id, tenant_id, purchase_order_id, supplier_product_id, product_id,
                         product_name_snapshot, product_sku_snapshot, supplier_sku_snapshot, quantity, unit_id,
                         unit_symbol_snapshot, purchase_to_base_factor, unit_cost, subtotal)
                    VALUES (?, ?, ?, ?, ?, ?, ?, 'PROV-X', 1, ?, 'u', 1, ?::numeric, ?::numeric)
                    """, UUID.randomUUID(), tenant, order, supplierProduct, product, line[0], line[1], unit,
                    line[2], line[2]);
        }
        return new Fixture(tenant, order);
    }

    private record Fixture(UUID tenant, UUID order) {}
}
