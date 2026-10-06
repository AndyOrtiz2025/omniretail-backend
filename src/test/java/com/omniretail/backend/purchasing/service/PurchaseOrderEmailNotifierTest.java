package com.omniretail.backend.purchasing.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.omniretail.backend.administration.entity.Branch;
import com.omniretail.backend.administration.entity.Supplier;
import com.omniretail.backend.purchasing.entity.PurchaseOrder;
import com.omniretail.backend.purchasing.entity.PurchaseOrderItem;
import com.omniretail.backend.shared.notification.EmailPurpose;
import com.omniretail.backend.shared.notification.EmailRequestedEvent;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.context.ApplicationEventPublisher;

class PurchaseOrderEmailNotifierTest {

    private final ApplicationEventPublisher publisher = mock(ApplicationEventPublisher.class);
    private final PurchaseOrderEmailNotifier notifier = new PurchaseOrderEmailNotifier(publisher);
    private final UUID tenantId = UUID.randomUUID();

    @Test
    void approvedOrderEmitsTenantScopedEmailToSupplierWithFormattedDetails() {
        PurchaseOrder order = PurchaseOrder.builder()
                .number("OC-2026-0001")
                .expectedDate(LocalDate.of(2026, 10, 15))
                .notes("Entregar en porton 2 antes del mediodia.")
                .total(new BigDecimal("205.00"))
                .build();
        order.setTenantId(tenantId);

        Supplier supplier = Supplier.builder()
                .name("Distribuidora Central, S.A.")
                .email("ventas@distribuidora.com")
                .build();

        Branch branch = Branch.builder()
                .name("Sucursal Central")
                .address("Zona 10, Ciudad de Guatemala")
                .build();

        PurchaseOrderItem item1 = PurchaseOrderItem.builder()
                .quantity(new BigDecimal("10.000"))
                .unitSymbolSnapshot("bx")
                .productNameSnapshot("Aceite Vegetal")
                .productSkuSnapshot("ACE-001")
                .unitCost(new BigDecimal("15.50"))
                .subtotal(new BigDecimal("155.00"))
                .build();

        PurchaseOrderItem item2 = PurchaseOrderItem.builder()
                .quantity(new BigDecimal("5.000"))
                .unitSymbolSnapshot("un")
                .productNameSnapshot("Harina de Trigo")
                .productSkuSnapshot("HAR-002")
                .unitCost(new BigDecimal("10.00"))
                .subtotal(new BigDecimal("50.00"))
                .build();

        notifier.notifyOrderApproved(order, supplier, branch, List.of(item1, item2));

        EmailRequestedEvent event = captured();
        assertThat(event.message().tenantId()).isEqualTo(tenantId);
        assertThat(event.message().purpose()).isEqualTo(EmailPurpose.PURCHASE_ORDER);
        assertThat(event.message().purpose().scope()).isEqualTo(EmailPurpose.Scope.TENANT);
        assertThat(event.message().to()).isEqualTo("ventas@distribuidora.com");
        assertThat(event.message().subject()).isEqualTo("Orden de compra OC-2026-0001");

        String body = event.message().body();
        assertThat(body)
                .contains("Estimado(a) Distribuidora Central, S.A.:")
                .contains("OC-2026-0001")
                .contains("2026-10-15")
                .contains("Sucursal Central")
                .contains("Zona 10, Ciudad de Guatemala")
                .contains("10.000 bx | Aceite Vegetal (SKU: ACE-001) | Costo unitario: Q15.50 | Subtotal: Q155.00")
                .contains("5.000 un | Harina de Trigo (SKU: HAR-002) | Costo unitario: Q10.00 | Subtotal: Q50.00")
                .contains("Total: Q205.00")
                .contains("Entregar en porton 2 antes del mediodia.")
                .contains("Saludos cordiales.");
    }

    @Test
    void trimsSupplierEmailAddress() {
        PurchaseOrder order = PurchaseOrder.builder()
                .number("OC-2026-0002")
                .total(new BigDecimal("100.00"))
                .build();
        order.setTenantId(tenantId);

        Supplier supplier = Supplier.builder()
                .name("Proveedor Espacios")
                .email("   proveedor@espacios.com   ")
                .build();

        notifier.notifyOrderApproved(order, supplier, null, List.of());

        EmailRequestedEvent event = captured();
        assertThat(event.message().to()).isEqualTo("proveedor@espacios.com");
    }

    @Test
    void doesNotEmitEventWhenSupplierEmailIsMissingOrBlank() {
        PurchaseOrder order = PurchaseOrder.builder().number("OC-999").build();
        order.setTenantId(tenantId);

        notifier.notifyOrderApproved(order, null, null, List.of());
        notifier.notifyOrderApproved(order, Supplier.builder().email(null).build(), null, List.of());
        notifier.notifyOrderApproved(order, Supplier.builder().email("").build(), null, List.of());
        notifier.notifyOrderApproved(order, Supplier.builder().email("   ").build(), null, List.of());
        notifier.notifyOrderApproved(null, Supplier.builder().email("test@test.com").build(), null, List.of());

        verify(publisher, never()).publishEvent(any());
    }

    @Test
    void handlesNullBranchAndNullItemsGracefully() {
        PurchaseOrder order = PurchaseOrder.builder()
                .number("OC-2026-MIN")
                .total(new BigDecimal("0.00"))
                .build();
        order.setTenantId(tenantId);

        Supplier supplier = Supplier.builder()
                .name("Proveedor Minimal")
                .email("minimal@proveedor.com")
                .build();

        notifier.notifyOrderApproved(order, supplier, null, null);

        EmailRequestedEvent event = captured();
        assertThat(event.message().to()).isEqualTo("minimal@proveedor.com");
        assertThat(event.message().body())
                .contains("OC-2026-MIN")
                .contains("Total: Q0.00")
                .doesNotContain("Sucursal de recepcion")
                .doesNotContain("Productos solicitados");
    }

    private EmailRequestedEvent captured() {
        ArgumentCaptor<EmailRequestedEvent> captor = ArgumentCaptor.forClass(EmailRequestedEvent.class);
        verify(publisher).publishEvent(captor.capture());
        return captor.getValue();
    }
}
