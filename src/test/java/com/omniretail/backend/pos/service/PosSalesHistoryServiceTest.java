package com.omniretail.backend.pos.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;

import com.omniretail.backend.TestcontainersConfiguration;
import com.omniretail.backend.administration.entity.UserType;
import com.omniretail.backend.administration.service.BranchAccessResolver;
import com.omniretail.backend.administration.service.BranchAccessResolver.BranchAccess;
import com.omniretail.backend.ecommerce.entity.DeliveryMethod;
import com.omniretail.backend.ecommerce.entity.OrderStatus;
import com.omniretail.backend.pos.dto.PosSalesHistoryPageResponse;
import com.omniretail.backend.pos.entity.SaleStatus;
import com.omniretail.backend.shared.exception.BusinessException;
import com.omniretail.backend.shared.security.AuthenticatedUser;
import com.omniretail.backend.shared.security.CurrentUser;
import com.omniretail.backend.shared.security.TenantCapabilityGuard;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

@SpringBootTest
@ActiveProfiles("test")
@Import(TestcontainersConfiguration.class)
class PosSalesHistoryServiceTest {

    @Autowired private PosSalesHistoryService service;
    @Autowired private JdbcTemplate jdbc;
    @MockitoBean private CurrentUser currentUser;
    @MockitoBean private BranchAccessResolver branchAccess;
    @MockitoBean private TenantCapabilityGuard capability;

    private Fixture fixture;

    @BeforeEach
    void setUp() {
        fixture = fixture("America/Guatemala");
        useFixture(fixture);
    }

    @Test
    void pagesSalesStablyAndSummarizesTheWholeFilteredResultWithoutDoubleCounting() {
        UUID first = sale(fixture, "POS-001", SaleStatus.completed,
                Instant.parse("2026-10-05T14:00:00Z"), null, null);
        UUID second = sale(fixture, "POS-002", SaleStatus.completed,
                Instant.parse("2026-10-05T15:00:00Z"), null, null);
        item(first, "SKU-A", "Producto A");
        item(first, "SKU-B", "Producto B");
        payment(first, "cash");
        payment(first, "card");

        PosSalesHistoryPageResponse result = search(PageRequest.of(0, 1));

        assertThat(result.items()).singleElement().satisfies(row -> {
            assertThat(row.saleId()).isEqualTo(second);
            assertThat(row.deliveryMethod()).isEqualTo(DeliveryMethod.immediate);
            assertThat(row.operationalStatus()).isNull();
        });
        assertThat(result.totalItems()).isEqualTo(2);
        assertThat(result.summary().total()).isEqualTo(2);
        assertThat(result.summary().completed()).isEqualTo(2);
    }

    @Test
    void appliesSaleStatusToPageAndEverySummaryBucket() {
        sale(fixture, "POS-C", SaleStatus.completed, Instant.parse("2026-10-05T10:00:00Z"), null, null);
        sale(fixture, "POS-P", SaleStatus.partially_returned, Instant.parse("2026-10-05T11:00:00Z"), null, null);
        sale(fixture, "POS-R", SaleStatus.returned, Instant.parse("2026-10-05T12:00:00Z"), null, null);
        sale(fixture, "POS-X", SaleStatus.cancelled, Instant.parse("2026-10-05T13:00:00Z"), null, null);

        PosSalesHistoryPageResponse result = service.search(
                fixture.branchId(), null, null, null, SaleStatus.returned,
                null, null, PageRequest.of(0, 20));

        assertThat(result.items()).singleElement().satisfies(row ->
                assertThat(row.status()).isEqualTo(SaleStatus.returned));
        assertThat(result.summary().total()).isEqualTo(1);
        assertThat(result.summary().completed()).isZero();
        assertThat(result.summary().partiallyReturned()).isZero();
        assertThat(result.summary().returned()).isEqualTo(1);
        assertThat(result.summary().cancelled()).isZero();
    }

    @Test
    void searchesBySaleCustomerOrderAndItemDataInTheDatabase() {
        UUID customer = customer(fixture, "Ana Operadora");
        UUID byNumber = sale(fixture, "POS-BUSCABLE", SaleStatus.completed,
                Instant.parse("2026-10-05T10:00:00Z"), null, null);
        UUID byCustomer = sale(fixture, "POS-CLIENTE", SaleStatus.completed,
                Instant.parse("2026-10-05T11:00:00Z"), null, customer);
        UUID order = order(fixture, "ORD-ESPECIAL", DeliveryMethod.home_delivery,
                OrderStatus.picking, "Cliente Invitado");
        UUID byOrder = sale(fixture, "POS-ORDER", SaleStatus.completed,
                Instant.parse("2026-10-05T12:00:00Z"), order, null);
        UUID byItem = sale(fixture, "POS-ITEM", SaleStatus.completed,
                Instant.parse("2026-10-05T13:00:00Z"), null, null);
        item(byItem, "SKU-UNICO", "Nombre Inconfundible");

        assertSingleSearch("buscable", byNumber);
        assertSingleSearch("ana operadora", byCustomer);
        assertSingleSearch("ord-especial", byOrder);
        assertSingleSearch("sku-unico", byItem);
        assertSingleSearch("inconfundible", byItem);
        assertSingleSearch("cliente invitado", byOrder);
    }

    @Test
    void filtersImmediateHomeDeliveryStorePickupAndOperationalStatus() {
        UUID immediate = sale(fixture, "POS-I", SaleStatus.completed,
                Instant.parse("2026-10-05T10:00:00Z"), null, null);
        UUID homeOrder = order(fixture, "ORD-H", DeliveryMethod.home_delivery,
                OrderStatus.dispatched, "Casa");
        UUID home = sale(fixture, "POS-H", SaleStatus.completed,
                Instant.parse("2026-10-05T11:00:00Z"), homeOrder, null);
        UUID pickupOrder = order(fixture, "ORD-P", DeliveryMethod.store_pickup,
                OrderStatus.ready_for_pickup, "Tienda");
        UUID pickup = sale(fixture, "POS-P", SaleStatus.completed,
                Instant.parse("2026-10-05T12:00:00Z"), pickupOrder, null);

        assertThat(service.search(fixture.branchId(), null, null, null, null,
                DeliveryMethod.immediate, null, PageRequest.of(0, 20)).items())
                .extracting(row -> row.saleId()).containsExactly(immediate);
        assertThat(service.search(fixture.branchId(), null, null, null, null,
                DeliveryMethod.home_delivery, null, PageRequest.of(0, 20)).items())
                .singleElement().satisfies(row -> {
                    assertThat(row.saleId()).isEqualTo(home);
                    assertThat(row.deliveryMethod()).isEqualTo(DeliveryMethod.home_delivery);
                    assertThat(row.operationalStatus()).isEqualTo(OrderStatus.dispatched);
                });
        assertThat(service.search(fixture.branchId(), null, null, null, null,
                null, OrderStatus.ready_for_pickup, PageRequest.of(0, 20)).items())
                .extracting(row -> row.saleId()).containsExactly(pickup);
    }

    @Test
    void resolvesFiscalRegisteredAndLegacyContactNamesWithOriginalFallback() {
        UUID registeredCustomer = customer(fixture, "Cliente registrado");
        UUID registered = sale(fixture, "POS-REGISTERED", SaleStatus.completed,
                Instant.parse("2026-10-05T09:00:00Z"), null, registeredCustomer);

        UUID pickupOrder = order(fixture, "ORD-PICKUP-NAME", DeliveryMethod.store_pickup,
                OrderStatus.picking, "Temporal");
        jdbc.update("""
                UPDATE orders
                SET guest_customer = NULL,
                    store_pickup_contact = '{"recipientName":"  Persona que retira  "}'::jsonb
                WHERE id = ?
                """, pickupOrder);
        UUID pickup = sale(fixture, "POS-PICKUP-NAME", SaleStatus.completed,
                Instant.parse("2026-10-05T10:00:00Z"), pickupOrder, null);

        UUID deliveryOrder = order(fixture, "ORD-DELIVERY-NAME", DeliveryMethod.home_delivery,
                OrderStatus.picking, "Temporal");
        jdbc.update("""
                UPDATE orders
                SET guest_customer = NULL,
                    delivery_address = '{"recipientName":"  Persona que recibe  "}'::jsonb
                WHERE id = ?
                """, deliveryOrder);
        UUID delivery = sale(fixture, "POS-DELIVERY-NAME", SaleStatus.completed,
                Instant.parse("2026-10-05T11:00:00Z"), deliveryOrder, null);

        UUID fallback = sale(fixture, "POS-FALLBACK", SaleStatus.completed,
                Instant.parse("2026-10-05T12:00:00Z"), null, null);
        UUID fiscal = sale(fixture, "POS-FISCAL", SaleStatus.completed,
                Instant.parse("2026-10-05T13:00:00Z"), pickupOrder, null);
        jdbc.update("""
                UPDATE sales
                SET document_type = 'invoice', document_tax_id = '1234567-8',
                    document_legal_name = 'Nombre fiscal',
                    document_fiscal_address = 'Ciudad de Guatemala'
                WHERE id = ?
                """, fiscal);

        PosSalesHistoryPageResponse result = search(PageRequest.of(0, 20));

        assertThat(result.items()).filteredOn(row -> row.saleId().equals(registered))
                .singleElement().satisfies(row -> assertThat(row.customerDisplayName()).isEqualTo("Cliente registrado"));
        assertThat(result.items()).filteredOn(row -> row.saleId().equals(pickup))
                .singleElement().satisfies(row -> assertThat(row.customerDisplayName()).isEqualTo("Persona que retira"));
        assertThat(result.items()).filteredOn(row -> row.saleId().equals(delivery))
                .singleElement().satisfies(row -> assertThat(row.customerDisplayName()).isEqualTo("Persona que recibe"));
        assertThat(result.items()).filteredOn(row -> row.saleId().equals(fallback))
                .singleElement().satisfies(row -> assertThat(row.customerDisplayName()).isEqualTo("Consumidor final"));
        assertThat(result.items()).filteredOn(row -> row.saleId().equals(fiscal))
                .singleElement().satisfies(row -> assertThat(row.customerDisplayName()).isEqualTo("Nombre fiscal"));
    }

    @Test
    void interpretsDateBoundsInTenantTimezoneWithInclusiveLastDay() {
        UUID insideStart = sale(fixture, "POS-D1", SaleStatus.completed,
                Instant.parse("2026-10-05T06:00:00Z"), null, null);
        UUID insideEnd = sale(fixture, "POS-D2", SaleStatus.completed,
                Instant.parse("2026-10-06T05:59:59Z"), null, null);
        sale(fixture, "POS-OUT", SaleStatus.completed,
                Instant.parse("2026-10-06T06:00:00Z"), null, null);

        PosSalesHistoryPageResponse result = service.search(
                fixture.branchId(), null, LocalDate.parse("2026-10-05"), LocalDate.parse("2026-10-05"),
                null, null, null, PageRequest.of(0, 20));

        assertThat(result.items()).extracting(row -> row.saleId())
                .containsExactly(insideEnd, insideStart);
    }

    @Test
    void enforcesTenantBranchRangeAndPageSize() {
        sale(fixture, "POS-OWN", SaleStatus.completed,
                Instant.parse("2026-10-05T10:00:00Z"), null, null);
        Fixture otherTenant = fixture("America/Guatemala");
        sale(otherTenant, "POS-OTHER", SaleStatus.completed,
                Instant.parse("2026-10-05T11:00:00Z"), null, null);

        assertThat(search(PageRequest.of(0, 500)).pageSize()).isEqualTo(100);
        assertThat(search(PageRequest.of(0, 20)).items())
                .extracting(row -> row.saleNumber()).containsExactly("POS-OWN");

        given(branchAccess.resolve(any())).willReturn(new BranchAccess(false, Set.of()));
        assertThatThrownBy(() -> search(PageRequest.of(0, 20)))
                .isInstanceOfSatisfying(BusinessException.class,
                        error -> assertThat(error.getCode()).isEqualTo("BRANCH_NOT_FOUND"));
        useFixture(fixture);
        assertThatThrownBy(() -> service.search(
                fixture.branchId(), null, LocalDate.parse("2026-10-06"), LocalDate.parse("2026-10-05"),
                null, null, null, PageRequest.of(0, 20)))
                .isInstanceOfSatisfying(BusinessException.class,
                        error -> assertThat(error.getCode()).isEqualTo("INVALID_SALE_DATE_RANGE"));
    }

    @Test
    void returnsEmptyResultAndRejectsUnsupportedSort() {
        PosSalesHistoryPageResponse empty = search(PageRequest.of(0, 20));
        assertThat(empty.items()).isEmpty();
        assertThat(empty.summary().total()).isZero();

        assertThatThrownBy(() -> search(PageRequest.of(
                0, 20, Sort.by("total"))))
                .isInstanceOfSatisfying(BusinessException.class,
                        error -> assertThat(error.getCode()).isEqualTo("POS_SALES_HISTORY_SORT_INVALID"));
    }

    private PosSalesHistoryPageResponse search(PageRequest page) {
        return service.search(fixture.branchId(), null, null, null, null, null, null, page);
    }

    private void assertSingleSearch(String search, UUID expectedSale) {
        assertThat(service.search(
                fixture.branchId(), search, null, null, null, null, null, PageRequest.of(0, 20)).items())
                .extracting(row -> row.saleId())
                .containsExactly(expectedSale);
    }

    private void useFixture(Fixture value) {
        given(currentUser.require()).willReturn(new AuthenticatedUser(
                value.userId(), value.tenantId(), UserType.employee,
                UUID.randomUUID(), value.branchId(), value.branchId()));
        given(branchAccess.resolve(any())).willReturn(new BranchAccess(false, Set.of(value.branchId())));
    }

    private Fixture fixture(String timezone) {
        String suffix = UUID.randomUUID().toString().replace("-", "");
        UUID tenantId = UUID.randomUUID();
        UUID branchId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        UUID shiftId = UUID.randomUUID();
        jdbc.update("INSERT INTO tenants (id, name, slug, status, default_currency, timezone) VALUES (?, ?, ?, 'active', 'GTQ', ?)",
                tenantId, "Tienda " + suffix, "history-" + suffix, timezone);
        UUID productId = product(tenantId, suffix);
        jdbc.update("INSERT INTO branches (id, tenant_id, code, name, type, status) VALUES (?, ?, ?, ?, 'store', 'active')",
                branchId, tenantId, "H-" + suffix.substring(0, 8), "Sucursal " + suffix);
        jdbc.update("INSERT INTO users (id, tenant_id, name, email, type, status) VALUES (?, ?, 'Cajero', ?, 'employee', 'active')",
                userId, tenantId, userId + "@history.test");
        jdbc.update("INSERT INTO cash_shifts (id, tenant_id, branch_id, user_id, register_code, status, opened_at, opening_amount) VALUES (?, ?, ?, ?, 'HIST', 'open', now(), 100.00)",
                shiftId, tenantId, branchId, userId);
        return new Fixture(tenantId, branchId, userId, shiftId, productId);
    }

    private UUID product(UUID tenantId, String suffix) {
        UUID categoryId = UUID.randomUUID();
        UUID unitId = UUID.randomUUID();
        UUID productId = UUID.randomUUID();
        jdbc.update("INSERT INTO categories (id, tenant_id, name, slug, status) VALUES (?, ?, ?, ?, 'active')",
                categoryId, tenantId, "Categoria " + suffix, "category-" + suffix);
        jdbc.update("INSERT INTO units (id, tenant_id, code, name, symbol, category, allows_decimals, status) VALUES (?, ?, ?, 'Unidad', 'u', 'unit', false, 'active')",
                unitId, tenantId, "U-" + suffix.substring(0, 8));
        jdbc.update("INSERT INTO products (id, tenant_id, sku, name, category_id, base_unit_id) VALUES (?, ?, ?, ?, ?, ?)",
                productId, tenantId, "P-" + suffix, "Producto " + suffix, categoryId, unitId);
        return productId;
    }

    private UUID sale(
            Fixture value,
            String number,
            SaleStatus status,
            Instant createdAt,
            UUID orderId,
            UUID customerId) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO sales (id, tenant_id, branch_id, number, customer_id, source_order_id,
                    cash_shift_id, status, subtotal, discount_total, tax_total, total,
                    created_by_user_id, created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, 10.00, 0.00, 0.00, 10.00, ?, ?, ?)
                """, id, value.tenantId(), value.branchId(), number, customerId, orderId,
                value.shiftId(), status.name(), value.userId(),
                Timestamp.from(createdAt), Timestamp.from(createdAt));
        return id;
    }

    private UUID order(
            Fixture value,
            String number,
            DeliveryMethod deliveryMethod,
            OrderStatus status,
            String guestName) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO orders (id, tenant_id, branch_id, order_number, source, guest_customer,
                    status, delivery_method, transport_mode, subtotal, discount_total,
                    shipping_total, total, tracking_token)
                VALUES (?, ?, ?, ?, 'pos', CAST(? AS jsonb), ?, ?, 'none',
                    10.00, 0.00, 0.00, 10.00, ?)
                """, id, value.tenantId(), value.branchId(), number,
                "{\"name\":\"" + guestName + "\"}", status.name(), deliveryMethod.name(), UUID.randomUUID().toString());
        return id;
    }

    private UUID customer(Fixture value, String name) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO customers (id, tenant_id, code, name, email, status) VALUES (?, ?, ?, ?, ?, 'active')",
                id, value.tenantId(), "C-" + id.toString().substring(0, 8), name, id + "@customer.test");
        return id;
    }

    private void item(UUID saleId, String sku, String name) {
        jdbc.update("INSERT INTO sale_items (id, sale_id, product_id, sku_snapshot, name_snapshot, quantity, unit_price, discount, subtotal) VALUES (?, ?, ?, ?, ?, 1.000, 10.00, 0.00, 10.00)",
                UUID.randomUUID(), saleId, fixture.productId(), sku, name);
    }

    private void payment(UUID saleId, String method) {
        jdbc.update("INSERT INTO payments (id, tenant_id, sale_id, method, status, amount, currency) VALUES (?, ?, ?, ?, 'approved', 5.00, 'GTQ')",
                UUID.randomUUID(), fixture.tenantId(), saleId, method);
    }

    private record Fixture(UUID tenantId, UUID branchId, UUID userId, UUID shiftId, UUID productId) {}
}
