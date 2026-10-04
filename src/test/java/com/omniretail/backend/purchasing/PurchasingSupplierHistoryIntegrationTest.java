package com.omniretail.backend.purchasing;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.omniretail.backend.TestcontainersConfiguration;
import com.omniretail.backend.administration.entity.User;
import com.omniretail.backend.administration.entity.UserType;
import com.omniretail.backend.administration.service.BranchAccessResolver;
import com.omniretail.backend.administration.service.BranchAccessResolver.BranchAccess;
import com.omniretail.backend.auth.entity.Session;
import com.omniretail.backend.auth.service.JwtService;
import com.omniretail.backend.auth.service.SessionService;
import com.omniretail.backend.shared.security.PermissionResolver;
import com.omniretail.backend.shared.security.SaasCapability;
import com.omniretail.backend.shared.security.TenantEntitlementResolver;
import com.omniretail.backend.shared.security.TenantEntitlements;
import java.time.Instant;
import java.util.EnumSet;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;

/** Productos e incidencias del detalle de proveedores en Compras (lecturas operacionales). */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(TestcontainersConfiguration.class)
class PurchasingSupplierHistoryIntegrationTest {

    private static final String BASE = "/api/v1/purchasing/suppliers/";

    @Autowired private MockMvc mvc;
    @Autowired private JwtService jwtService;
    @Autowired private JdbcTemplate jdbc;
    @MockitoBean private SessionService sessions;
    @MockitoBean private PermissionResolver permissions;
    @MockitoBean private TenantEntitlementResolver entitlements;
    @MockitoBean private BranchAccessResolver branchAccess;

    @BeforeEach
    void setUp() {
        given(sessions.isActive(any(), any())).willReturn(true);
        given(permissions.hasPermission(any(), any(), anyString())).willReturn(false);
        given(permissions.hasPermission(any(), any(), eq("purchasing.orders.read"))).willReturn(true);
        given(entitlements.resolve(any())).willReturn(
                new TenantEntitlements(true, true, EnumSet.allOf(SaasCapability.class)));
        given(branchAccess.resolve(any())).willReturn(new BranchAccess(true, Set.of()));
    }

    // -------------------------------------------------------------- products

    @Test
    void productsArePaginatedOneBasedWithStableNameOrderAndNoTenantId() throws Exception {
        Tenant t = tenant();
        UUID supplier = supplier(t, "Proveedor", "active");
        UUID unit = unit(t, "cj", "active");
        supplierProduct(t, supplier, product(t, "Cebolla", "SKU-C", "published"), unit, "S-3", true, "3.00");
        supplierProduct(t, supplier, product(t, "Aceite", "SKU-A", "published"), unit, "S-1", true, "1.00");
        supplierProduct(t, supplier, product(t, "Bebida", "SKU-B", "published"), unit, "S-2", true, "2.00");

        mvc.perform(get(BASE + supplier + "/products").header("Authorization", token(t))
                        .param("page", "1").param("size", "2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.page").value(1))
                .andExpect(jsonPath("$.totalItems").value(3))
                .andExpect(jsonPath("$.totalPages").value(2))
                .andExpect(jsonPath("$.items[0].productName").value("Aceite"))
                .andExpect(jsonPath("$.items[1].productName").value("Bebida"))
                .andExpect(jsonPath("$.items[0].productSku").value("SKU-A"))
                .andExpect(jsonPath("$.items[0].supplierSku").value("S-1"))
                .andExpect(jsonPath("$.items[0].purchaseUnitSymbol").value("cj"))
                .andExpect(jsonPath("$.items[0].lastCost").value(1.00))
                .andExpect(jsonPath("$.items[0].tenantId").doesNotExist());
        mvc.perform(get(BASE + supplier + "/products").header("Authorization", token(t))
                        .param("page", "2").param("size", "2"))
                .andExpect(jsonPath("$.page").value(2))
                .andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.items[0].productName").value("Cebolla"));
    }

    @Test
    void productsSearchByNameSkuAndSupplierSkuIsCaseInsensitiveAndLiteral() throws Exception {
        Tenant t = tenant();
        UUID supplier = supplier(t, "Proveedor", "active");
        UUID unit = unit(t, "u", "active");
        supplierProduct(t, supplier, product(t, "Harina Premium", "HP-100", "published"), unit, "PROV-77", true, "5");
        supplierProduct(t, supplier, product(t, "Azucar", "AZ-200", "published"), unit, "PROV-88", true, "6");

        for (String term : new String[] {"harina", "hp-100", "prov-77"}) {
            mvc.perform(get(BASE + supplier + "/products").header("Authorization", token(t))
                            .param("search", term))
                    .andExpect(jsonPath("$.totalItems").value(1))
                    .andExpect(jsonPath("$.items[0].productName").value("Harina Premium"));
        }
        mvc.perform(get(BASE + supplier + "/products").header("Authorization", token(t)).param("search", "%"))
                .andExpect(jsonPath("$.totalItems").value(0));
    }

    @Test
    void productsActiveFilterAndHistoricalStatesAreNotHidden() throws Exception {
        Tenant t = tenant();
        UUID supplier = supplier(t, "Archivado", "archived");
        UUID archivedUnit = unit(t, "old", "archived");
        UUID liveUnit = unit(t, "u", "active");
        supplierProduct(t, supplier, product(t, "Activo", "S1", "published"), liveUnit, null, true, "1");
        supplierProduct(t, supplier, product(t, "Inactivo", "S2", "published"), liveUnit, null, false, "1");
        supplierProduct(t, supplier, product(t, "ProductoArchivado", "S3", "archived"), archivedUnit, null, true, "1");

        mvc.perform(get(BASE + supplier + "/products").header("Authorization", token(t)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalItems").value(3));
        mvc.perform(get(BASE + supplier + "/products").header("Authorization", token(t)).param("active", "true"))
                .andExpect(jsonPath("$.totalItems").value(2));
        mvc.perform(get(BASE + supplier + "/products").header("Authorization", token(t)).param("active", "false"))
                .andExpect(jsonPath("$.totalItems").value(1))
                .andExpect(jsonPath("$.items[0].productName").value("Inactivo"))
                .andExpect(jsonPath("$.items[0].active").value(false));
    }

    @Test
    void productsIncludeCostTiersOrderedByMinQuantityPerItem() throws Exception {
        Tenant t = tenant();
        UUID supplier = supplier(t, "Proveedor", "active");
        UUID unit = unit(t, "u", "active");
        UUID first = supplierProduct(t, supplier, product(t, "Con tramos", "T1", "published"), unit, null, true, "10");
        supplierProduct(t, supplier, product(t, "Sin tramos", "T2", "published"), unit, null, true, "10");
        tier(t, first, "100", "8.50");
        tier(t, first, "10", "9.50");

        mvc.perform(get(BASE + supplier + "/products").header("Authorization", token(t)).param("search", "tramos"))
                .andExpect(jsonPath("$.items[0].productName").value("Con tramos"))
                .andExpect(jsonPath("$.items[0].costTiers.length()").value(2))
                .andExpect(jsonPath("$.items[0].costTiers[0].minQuantity").value(10))
                .andExpect(jsonPath("$.items[0].costTiers[0].unitCost").value(9.50))
                .andExpect(jsonPath("$.items[0].costTiers[1].minQuantity").value(100))
                .andExpect(jsonPath("$.items[1].costTiers.length()").value(0));
    }

    @Test
    void productsAreIsolatedByTenantAndUnknownSupplierIsNotFound() throws Exception {
        Tenant t = tenant();
        Tenant other = tenant();
        UUID mine = supplier(t, "Propio", "active");
        UUID foreign = supplier(other, "Ajeno", "active");
        UUID unit = unit(other, "u", "active");
        supplierProduct(other, foreign, product(other, "Ajeno", "X1", "published"), unit, null, true, "1");

        mvc.perform(get(BASE + mine + "/products").header("Authorization", token(t)))
                .andExpect(jsonPath("$.totalItems").value(0));
        mvc.perform(get(BASE + foreign + "/products").header("Authorization", token(t)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("SUPPLIER_NOT_FOUND"));
        mvc.perform(get(BASE + UUID.randomUUID() + "/products").header("Authorization", token(t)))
                .andExpect(status().isNotFound());
    }

    // ------------------------------------------------------------- incidents

    @Test
    void incidentsAreListedNewestFirstWithDocumentNumbersAndLineProduct() throws Exception {
        Tenant t = tenant();
        UUID branch = branch(t);
        UUID supplier = supplier(t, "Proveedor", "active");
        Doc doc = document(t, branch, supplier, "OC-100", "REC-100", "Producto de linea");
        UUID older = incident(t, branch, doc, true, "damaged", "open", "2", "2026-01-01T10:00:00Z", null);
        UUID newer = incident(t, branch, doc, false, "other", "open", null, "2026-02-01T10:00:00Z", null);

        mvc.perform(get(BASE + supplier + "/incidents").header("Authorization", token(t)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalItems").value(2))
                .andExpect(jsonPath("$.items[0].id").value(newer.toString()))
                .andExpect(jsonPath("$.items[0].productId").doesNotExist())
                .andExpect(jsonPath("$.items[0].productName").doesNotExist())
                .andExpect(jsonPath("$.items[0].quantityAffected").doesNotExist())
                .andExpect(jsonPath("$.items[1].id").value(older.toString()))
                .andExpect(jsonPath("$.items[1].incidentType").value("damaged"))
                .andExpect(jsonPath("$.items[1].status").value("open"))
                .andExpect(jsonPath("$.items[1].quantityAffected").value(2))
                .andExpect(jsonPath("$.items[1].notes").value("Nota"))
                .andExpect(jsonPath("$.items[1].receiptNumber").value("REC-100"))
                .andExpect(jsonPath("$.items[1].purchaseOrderNumber").value("OC-100"))
                .andExpect(jsonPath("$.items[1].goodsReceiptId").value(doc.receipt().toString()))
                .andExpect(jsonPath("$.items[1].purchaseOrderId").value(doc.order().toString()))
                .andExpect(jsonPath("$.items[1].branchId").value(branch.toString()))
                .andExpect(jsonPath("$.items[1].productId").value(doc.product().toString()))
                .andExpect(jsonPath("$.items[1].productName").value("Producto de linea"))
                .andExpect(jsonPath("$.items[1].createdByUserId").doesNotExist())
                .andExpect(jsonPath("$.items[1].resolvedByUserId").doesNotExist());
    }

    @Test
    void incidentsWithSameTimestampAreOrderedByIdDescendingAndPaginated() throws Exception {
        Tenant t = tenant();
        UUID branch = branch(t);
        UUID supplier = supplier(t, "Proveedor", "active");
        Doc doc = document(t, branch, supplier, "OC-1", "REC-1", "P");
        UUID low = new UUID(0L, 1L);
        UUID high = new UUID(0L, 2L);
        incidentWithId(low, t, branch, doc, "2026-03-01T10:00:00Z");
        incidentWithId(high, t, branch, doc, "2026-03-01T10:00:00Z");

        mvc.perform(get(BASE + supplier + "/incidents").header("Authorization", token(t))
                        .param("page", "1").param("size", "1"))
                .andExpect(jsonPath("$.totalItems").value(2))
                .andExpect(jsonPath("$.totalPages").value(2))
                .andExpect(jsonPath("$.items[0].id").value(high.toString()));
        mvc.perform(get(BASE + supplier + "/incidents").header("Authorization", token(t))
                        .param("page", "2").param("size", "1"))
                .andExpect(jsonPath("$.page").value(2))
                .andExpect(jsonPath("$.items[0].id").value(low.toString()));
    }

    @Test
    void incidentsStatusFilterUsesRealStatuses() throws Exception {
        Tenant t = tenant();
        UUID branch = branch(t);
        UUID supplier = supplier(t, "Proveedor", "active");
        Doc doc = document(t, branch, supplier, "OC-1", "REC-1", "P");
        incident(t, branch, doc, false, "other", "open", null, "2026-01-01T10:00:00Z", null);
        UUID resolved = incident(t, branch, doc, false, "other", "resolved", null, "2026-01-02T10:00:00Z", t.user());

        mvc.perform(get(BASE + supplier + "/incidents").header("Authorization", token(t)).param("status", "resolved"))
                .andExpect(jsonPath("$.totalItems").value(1))
                .andExpect(jsonPath("$.items[0].id").value(resolved.toString()))
                .andExpect(jsonPath("$.items[0].resolvedAt").isNotEmpty());
        mvc.perform(get(BASE + supplier + "/incidents").header("Authorization", token(t)).param("status", "open"))
                .andExpect(jsonPath("$.totalItems").value(1));
        mvc.perform(get(BASE + supplier + "/incidents").header("Authorization", token(t)).param("status", "bogus"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void incidentsRespectBranchAuthorization() throws Exception {
        Tenant t = tenant();
        UUID allowed = branch(t);
        UUID forbidden = branch(t);
        UUID supplier = supplier(t, "Proveedor", "active");
        incident(t, allowed, document(t, allowed, supplier, "OC-A", "REC-A", "P"), false, "other", "open", null,
                "2026-01-01T10:00:00Z", null);
        incident(t, forbidden, document(t, forbidden, supplier, "OC-B", "REC-B", "P"), false, "other", "open", null,
                "2026-01-02T10:00:00Z", null);
        given(branchAccess.resolve(any())).willReturn(new BranchAccess(false, Set.of(allowed)));

        // Sin branchId solo se ven las sucursales autorizadas.
        mvc.perform(get(BASE + supplier + "/incidents").header("Authorization", token(t)))
                .andExpect(jsonPath("$.totalItems").value(1))
                .andExpect(jsonPath("$.items[0].branchId").value(allowed.toString()));
        mvc.perform(get(BASE + supplier + "/incidents").header("Authorization", token(t))
                        .param("branchId", allowed.toString()))
                .andExpect(jsonPath("$.totalItems").value(1));
        mvc.perform(get(BASE + supplier + "/incidents").header("Authorization", token(t))
                        .param("branchId", forbidden.toString()))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("BRANCH_ACCESS_DENIED"));

        given(branchAccess.resolve(any())).willReturn(new BranchAccess(false, Set.of()));
        mvc.perform(get(BASE + supplier + "/incidents").header("Authorization", token(t)))
                .andExpect(jsonPath("$.totalItems").value(0));

        given(branchAccess.resolve(any())).willReturn(new BranchAccess(true, Set.of()));
        mvc.perform(get(BASE + supplier + "/incidents").header("Authorization", token(t))
                        .param("branchId", forbidden.toString()))
                .andExpect(jsonPath("$.totalItems").value(1))
                .andExpect(jsonPath("$.items[0].branchId").value(forbidden.toString()));
    }

    @Test
    void incidentsAreIsolatedByTenantAndSupplier() throws Exception {
        Tenant t = tenant();
        Tenant other = tenant();
        UUID branch = branch(t);
        UUID otherBranch = branch(other);
        UUID supplier = supplier(t, "Propio", "active");
        UUID sibling = supplier(t, "Hermano", "active");
        UUID foreign = supplier(other, "Ajeno", "active");
        incident(t, branch, document(t, branch, supplier, "OC-1", "REC-1", "P"), false, "other", "open", null,
                "2026-01-01T10:00:00Z", null);
        incident(t, branch, document(t, branch, sibling, "OC-2", "REC-2", "P"), false, "other", "open", null,
                "2026-01-01T10:00:00Z", null);
        incident(other, otherBranch, document(other, otherBranch, foreign, "OC-3", "REC-3", "P"), false, "other",
                "open", null, "2026-01-01T10:00:00Z", null);

        mvc.perform(get(BASE + supplier + "/incidents").header("Authorization", token(t)))
                .andExpect(jsonPath("$.totalItems").value(1))
                .andExpect(jsonPath("$.items[0].purchaseOrderNumber").value("OC-1"));
        mvc.perform(get(BASE + foreign + "/incidents").header("Authorization", token(t)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("SUPPLIER_NOT_FOUND"));
        mvc.perform(get(BASE + UUID.randomUUID() + "/incidents").header("Authorization", token(t)))
                .andExpect(status().isNotFound());
    }

    // ------------------------------------------------------ permissions / misc

    @Test
    void bothEndpointsRequireOperationalPermissionAndPurchasingCapabilityAndNeverAdminPermission() throws Exception {
        Tenant t = tenant();
        UUID supplier = supplier(t, "Proveedor", "active");
        given(permissions.hasPermission(any(), any(), eq("purchasing.orders.read"))).willReturn(false);

        for (String path : new String[] {"/products", "/incidents"}) {
            mvc.perform(get(BASE + supplier + path).header("Authorization", token(t)))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.code").value("ACCESS_DENIED"));
        }
        // Receiving no basta: estas lecturas son del read model de Compras.
        given(permissions.hasPermission(any(), any(), eq("receiving.receipts.read"))).willReturn(true);
        mvc.perform(get(BASE + supplier + "/incidents").header("Authorization", token(t)))
                .andExpect(status().isForbidden());

        given(permissions.hasPermission(any(), any(), eq("purchasing.orders.approve"))).willReturn(true);
        for (String path : new String[] {"/products", "/incidents"}) {
            mvc.perform(get(BASE + supplier + path).header("Authorization", token(t)))
                    .andExpect(status().isOk());
        }
        given(entitlements.resolve(t.id())).willReturn(
                new TenantEntitlements(true, true, EnumSet.of(SaasCapability.inventory)));
        for (String path : new String[] {"/products", "/incidents"}) {
            mvc.perform(get(BASE + supplier + path).header("Authorization", token(t)))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.code").value("CAPABILITY_REQUIRED"));
        }
        verify(permissions, never()).hasPermission(any(), any(), eq("admin.suppliers.manage"));
    }

    @Test
    void existingSupplierRoutesAreNotShadowedByTheNewPaths() throws Exception {
        Tenant t = tenant();
        UUID supplier = supplier(t, "Proveedor", "active");

        mvc.perform(get(BASE + "active").header("Authorization", token(t)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].name").value("Proveedor"));
        mvc.perform(get(BASE + supplier).header("Authorization", token(t)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("Proveedor"));
        mvc.perform(get(BASE.substring(0, BASE.length() - 1)).header("Authorization", token(t)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalItems").value(1));
    }

    // --------------------------------------------------------------- fixtures

    private record Tenant(UUID id, UUID user) {}

    private record Doc(UUID order, UUID receipt, UUID item, UUID product) {}

    private Tenant tenant() {
        UUID id = UUID.randomUUID();
        UUID user = UUID.randomUUID();
        UUID branch = UUID.randomUUID();
        String suffix = id.toString();
        jdbc.update("INSERT INTO tenants (id, name, slug, status, default_currency, timezone) VALUES (?, ?, ?, 'active', 'GTQ', 'America/Guatemala')",
                id, "Tenant " + suffix, "hist-" + suffix);
        jdbc.update("INSERT INTO branches (id, tenant_id, code, name, type, status) VALUES (?, ?, ?, 'Base', 'main', 'active')",
                branch, id, "B-" + suffix.substring(0, 8));
        jdbc.update("INSERT INTO users (id, tenant_id, name, email, type, status, branch_id) VALUES (?, ?, 'Comprador', ?, 'employee', 'active', ?)",
                user, id, user + "@test.local", branch);
        jdbc.update("INSERT INTO categories (id, tenant_id, name, slug) VALUES (?, ?, 'Cat', ?)",
                UUID.randomUUID(), id, "cat-" + suffix);
        return new Tenant(id, user);
    }

    private UUID branch(Tenant t) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO branches (id, tenant_id, code, name, type, status) VALUES (?, ?, ?, 'Sucursal', 'store', 'active')",
                id, t.id(), "B-" + id.toString().substring(0, 8));
        return id;
    }

    private UUID supplier(Tenant t, String name, String status) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO suppliers (id, tenant_id, name, status) VALUES (?, ?, ?, ?)", id, t.id(), name, status);
        return id;
    }

    private UUID unit(Tenant t, String symbol, String status) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO units (id, tenant_id, code, name, symbol, category, allows_decimals, status) VALUES (?, ?, ?, 'Unidad', ?, 'unit', true, ?)",
                id, t.id(), "U-" + id.toString().substring(0, 8), symbol, status);
        return id;
    }

    private UUID product(Tenant t, String name, String sku, String status) {
        UUID id = UUID.randomUUID();
        UUID category = jdbc.queryForObject("SELECT id FROM categories WHERE tenant_id = ?", UUID.class, t.id());
        UUID baseUnit = unit(t, "b", "active");
        jdbc.update("INSERT INTO products (id, tenant_id, sku, name, category_id, base_unit_id, status) VALUES (?, ?, ?, ?, ?, ?, ?)",
                id, t.id(), sku, name, category, baseUnit, status);
        return id;
    }

    private UUID supplierProduct(
            Tenant t, UUID supplier, UUID product, UUID unit, String supplierSku, boolean active, String cost) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO supplier_products
                    (id, tenant_id, supplier_id, product_id, supplier_sku, purchase_unit_id,
                     purchase_to_base_factor, last_cost, lead_time_days, minimum_order_quantity, active)
                VALUES (?, ?, ?, ?, ?, ?, 1, ?::numeric, 3, 1, ?)
                """, id, t.id(), supplier, product, supplierSku, unit, cost, active);
        return id;
    }

    private void tier(Tenant t, UUID supplierProduct, String minQuantity, String unitCost) {
        jdbc.update("INSERT INTO supplier_cost_tiers (id, tenant_id, supplier_product_id, min_quantity, unit_cost) VALUES (?, ?, ?, ?::numeric, ?::numeric)",
                UUID.randomUUID(), t.id(), supplierProduct, minQuantity, unitCost);
    }

    /** Orden + recepción + línea recibida (con su producto) de un proveedor. */
    private Doc document(Tenant t, UUID branch, UUID supplier, String orderNumber, String receiptNumber, String productName) {
        UUID unit = unit(t, "u", "active");
        UUID product = product(t, productName, "SKU-" + UUID.randomUUID(), "published");
        UUID supplierProduct = supplierProduct(t, supplier, product, unit, null, true, "1");
        UUID order = UUID.randomUUID();
        UUID orderItem = UUID.randomUUID();
        UUID receipt = UUID.randomUUID();
        UUID item = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO purchase_orders
                    (id, tenant_id, branch_id, number, supplier_id, supplier_name_snapshot, status,
                     subtotal, total, created_by_user_id)
                VALUES (?, ?, ?, ?, ?, 'Proveedor', 'approved', 10, 10, ?)
                """, order, t.id(), branch, orderNumber, supplier, t.user());
        jdbc.update("""
                INSERT INTO purchase_order_items
                    (id, tenant_id, purchase_order_id, supplier_product_id, product_id,
                     product_name_snapshot, product_sku_snapshot, quantity, unit_id,
                     unit_symbol_snapshot, purchase_to_base_factor, unit_cost, subtotal)
                VALUES (?, ?, ?, ?, ?, ?, 'SKU', 10, ?, 'u', 1, 1, 10)
                """, orderItem, t.id(), order, supplierProduct, product, productName, unit);
        jdbc.update("INSERT INTO goods_receipts (id, tenant_id, branch_id, purchase_order_id, number, status) VALUES (?, ?, ?, ?, ?, 'draft')",
                receipt, t.id(), branch, order, receiptNumber);
        jdbc.update("""
                INSERT INTO goods_receipt_items
                    (id, tenant_id, goods_receipt_id, purchase_order_item_id, product_id,
                     received_quantity, unit_id, unit_symbol_snapshot, purchase_to_base_factor,
                     base_quantity, unit_cost)
                VALUES (?, ?, ?, ?, ?, 5, ?, 'u', 1, 5, 1)
                """, item, t.id(), receipt, orderItem, product, unit);
        return new Doc(order, receipt, item, product);
    }

    private UUID incident(
            Tenant t, UUID branch, Doc doc, boolean forLine, String type, String status, String quantity,
            String createdAt, UUID resolvedBy) {
        UUID id = UUID.randomUUID();
        insertIncident(id, t, branch, doc, forLine, type, status, quantity, createdAt, resolvedBy);
        return id;
    }

    private void incidentWithId(UUID id, Tenant t, UUID branch, Doc doc, String createdAt) {
        insertIncident(id, t, branch, doc, false, "other", "open", null, createdAt, null);
    }

    private void insertIncident(
            UUID id, Tenant t, UUID branch, Doc doc, boolean forLine, String type, String status,
            String quantity, String createdAt, UUID resolvedBy) {
        boolean resolved = "resolved".equals(status);
        jdbc.update("""
                INSERT INTO receipt_incidents
                    (id, tenant_id, branch_id, goods_receipt_id, goods_receipt_item_id, incident_type, status,
                     quantity_affected, notes, created_by_user_id, resolved_by_user_id, resolved_at, created_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?::numeric, 'Nota', ?, ?, CASE WHEN ? THEN now() END, ?::timestamptz)
                """,
                id, t.id(), branch, doc.receipt(), forLine ? doc.item() : null, type, status,
                forLine ? (quantity == null ? "1" : quantity) : null, t.user(),
                resolved ? resolvedBy : null, resolved, createdAt);
    }

    private String token(Tenant t) {
        User user = User.builder()
                .name("Comprador")
                .email("purchasing-" + UUID.randomUUID() + "@test.local")
                .type(UserType.employee)
                .roleId(UUID.randomUUID())
                .build();
        user.setTenantId(t.id());
        ReflectionTestUtils.setField(user, "id", t.user());
        Session session = Session.builder()
                .id(UUID.randomUUID())
                .userId(user.getId())
                .expiresAt(Instant.now().plusSeconds(3600))
                .build();
        return "Bearer " + jwtService.generateToken(user, session);
    }
}
