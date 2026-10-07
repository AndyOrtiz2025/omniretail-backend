package com.omniretail.backend.purchasing.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.omniretail.backend.administration.entity.Branch;
import com.omniretail.backend.administration.entity.Supplier;
import com.omniretail.backend.administration.entity.Tenant;
import com.omniretail.backend.administration.repository.TenantRepository;
import com.omniretail.backend.purchasing.entity.PurchaseOrder;
import com.omniretail.backend.purchasing.entity.PurchaseOrderItem;
import com.omniretail.backend.shared.notification.EmailPurpose;
import com.omniretail.backend.shared.notification.EmailRequestedEvent;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.test.util.ReflectionTestUtils;

class PurchaseOrderEmailNotifierTest {

    private final ApplicationEventPublisher publisher = mock(ApplicationEventPublisher.class);
    private final TenantRepository tenants = mock(TenantRepository.class);
    private final PurchaseOrderEmailNotifier notifier = new PurchaseOrderEmailNotifier(publisher, tenants);
    private final UUID tenantId = UUID.randomUUID();

    @Test
    void approvedOrderEmitsOneTenantScopedEmailWithSummaryHtmlAndPdfReference() {
        Tenant tenant = Tenant.builder().name("Ferreteria Norte").legalName("Ferreteria Norte, S.A.").build();
        when(tenants.findById(tenantId)).thenReturn(Optional.of(tenant));
        PurchaseOrder order = order("OC-2026-0001");
        order.setExpectedDate(LocalDate.of(2026, 10, 15));
        order.setNotes("Entregar en porton 2 antes del mediodia.");
        order.setTotal(new BigDecimal("205.00"));
        Supplier supplier = Supplier.builder()
                .name("Distribuidora Central, S.A.")
                .email("ventas@distribuidora.com")
                .build();
        Branch branch = Branch.builder().name("Sucursal Central").address("Zona 10, Ciudad de Guatemala").build();

        notifier.notifyOrderApproved(order, supplier, branch, List.of(item("Aceite Vegetal", "ACE-001"), item("Harina", "HAR-002")));

        EmailRequestedEvent event = captured();
        assertThat(event.message().tenantId()).isEqualTo(tenantId);
        assertThat(event.message().purpose()).isEqualTo(EmailPurpose.PURCHASE_ORDER);
        assertThat(event.message().purpose().scope()).isEqualTo(EmailPurpose.Scope.TENANT);
        assertThat(event.message().to()).isEqualTo("ventas@distribuidora.com");
        assertThat(event.message().subject()).isEqualTo("Orden de compra OC-2026-0001 aprobada");

        String plain = event.message().body();
        assertThat(plain)
                .contains("Estimado(a) Distribuidora Central, S.A.:")
                .contains("OC-2026-0001")
                .contains("15/10/2026")
                .contains("Sucursal Central")
                .contains("Zona 10, Ciudad de Guatemala")
                .contains("Lineas de producto: 2")
                .contains("Total: Q205.00")
                .contains("PDF adjunto")
                .contains("Saludos cordiales.")
                .contains("Ferreteria Norte, S.A.")
                // Resumen: el detalle de productos va solo en el PDF.
                .doesNotContain("Aceite Vegetal")
                .doesNotContain("ACE-001")
                .doesNotContain("Productos solicitados");
        assertThat(plain.lines().count()).isLessThan(25);

        String html = event.message().html();
        assertThat(html)
                .isNotNull()
                .contains("<table")
                .contains("style=")
                .contains("Orden de compra aprobada")
                .contains("OC-2026-0001")
                .contains("Q205.00")
                .contains("PDF adjunto")
                .doesNotContain("<script")
                .doesNotContain("Aceite Vegetal");

        assertThat(event.message().attachments()).singleElement().satisfies(attachment -> {
            assertThat(attachment.type()).isEqualTo(PurchaseOrderPdfAttachmentResolver.TYPE);
            assertThat(attachment.resourceId()).isEqualTo(order.getId());
            assertThat(attachment.filename()).isEqualTo("OC-2026-0001-orden-compra.pdf");
            assertThat(attachment.mediaType()).isEqualTo("application/pdf");
        });
        // Nunca viajan bytes en el mensaje original: solo el descriptor.
        assertThat(event.message().attachmentContents()).isEmpty();
    }

    @Test
    void htmlBodyDoesNotEnumerateHundredsOfProductsAndStaysSmall() {
        PurchaseOrder order = order("OC-BIG");
        order.setTotal(new BigDecimal("99999.50"));
        Supplier supplier = Supplier.builder().name("Proveedor").email("p@x.com").build();
        List<PurchaseOrderItem> items = new ArrayList<>();
        for (int index = 0; index < 400; index++) {
            items.add(item("Producto numero " + index, "SKU-" + index));
        }

        notifier.notifyOrderApproved(order, supplier, null, items);

        EmailRequestedEvent event = captured();
        assertThat(event.message().html()).contains("400").doesNotContain("Producto numero 7")
                .hasSizeLessThan(8_000);
        assertThat(event.message().body()).contains("Lineas de producto: 400").doesNotContain("SKU-12");
    }

    @Test
    void escapesEveryVariableValueInTheHtmlBody() {
        PurchaseOrder order = order("OC-<b>1</b>");
        order.setTotal(BigDecimal.TEN);
        Supplier supplier = Supplier.builder()
                .name("<script>alert('x')</script> & Cia")
                .email("p@x.com")
                .build();
        Branch branch = Branch.builder().name("Sucursal \"A\" <i>").address("Calle <img src=x onerror=1>").build();
        Tenant tenant = Tenant.builder().name("Negocio <u>").build();
        when(tenants.findById(tenantId)).thenReturn(Optional.of(tenant));

        notifier.notifyOrderApproved(order, supplier, branch, List.of());

        String html = captured().message().html();
        assertThat(html)
                .doesNotContain("<script")
                .doesNotContain("<img")
                .doesNotContain("<b>1</b>")
                .doesNotContain("<i>")
                .doesNotContain("<u>")
                .contains("&lt;script&gt;")
                .contains("&amp; Cia")
                .contains("&lt;img");
    }

    @Test
    void trimsSupplierEmailAddressAndFallsBackToOmniRetailWhenTheTenantIsUnknown() {
        PurchaseOrder order = order("OC-2026-0002");
        order.setTotal(new BigDecimal("100.00"));
        Supplier supplier = Supplier.builder().name("Proveedor Espacios").email("   proveedor@espacios.com   ").build();

        notifier.notifyOrderApproved(order, supplier, null, List.of());

        EmailRequestedEvent event = captured();
        assertThat(event.message().to()).isEqualTo("proveedor@espacios.com");
        assertThat(event.message().body()).contains("OmniRetail");
    }

    @Test
    void doesNotEmitEventWhenSupplierEmailIsMissingOrBlank() {
        PurchaseOrder order = order("OC-999");

        notifier.notifyOrderApproved(order, null, null, List.of());
        notifier.notifyOrderApproved(order, Supplier.builder().email(null).build(), null, List.of());
        notifier.notifyOrderApproved(order, Supplier.builder().email("").build(), null, List.of());
        notifier.notifyOrderApproved(order, Supplier.builder().email("   ").build(), null, List.of());
        notifier.notifyOrderApproved(null, Supplier.builder().email("test@test.com").build(), null, List.of());

        verify(publisher, never()).publishEvent(any());
    }

    @Test
    void handlesNullBranchAndNullItemsGracefully() {
        PurchaseOrder order = order("OC-2026-MIN");
        order.setTotal(new BigDecimal("0.00"));
        Supplier supplier = Supplier.builder().name("Proveedor Minimal").email("minimal@proveedor.com").build();

        notifier.notifyOrderApproved(order, supplier, null, null);

        EmailRequestedEvent event = captured();
        assertThat(event.message().to()).isEqualTo("minimal@proveedor.com");
        assertThat(event.message().body())
                .contains("OC-2026-MIN")
                .contains("Total: Q0.00")
                .contains("Lineas de producto: 0")
                .doesNotContain("Sucursal de recepcion");
        assertThat(event.message().attachments()).hasSize(1);
    }

    private PurchaseOrder order(String number) {
        PurchaseOrder order = PurchaseOrder.builder().number(number).total(BigDecimal.ZERO).build();
        order.setTenantId(tenantId);
        ReflectionTestUtils.setField(order, "id", UUID.randomUUID());
        ReflectionTestUtils.setField(order, "createdAt", Instant.parse("2026-10-05T15:00:00Z"));
        return order;
    }

    private static PurchaseOrderItem item(String name, String sku) {
        return PurchaseOrderItem.builder()
                .quantity(BigDecimal.ONE)
                .unitSymbolSnapshot("un")
                .productNameSnapshot(name)
                .productSkuSnapshot(sku)
                .unitCost(BigDecimal.ONE)
                .subtotal(BigDecimal.ONE)
                .build();
    }

    private EmailRequestedEvent captured() {
        ArgumentCaptor<EmailRequestedEvent> captor = ArgumentCaptor.forClass(EmailRequestedEvent.class);
        verify(publisher).publishEvent(captor.capture());
        return captor.getValue();
    }
}
