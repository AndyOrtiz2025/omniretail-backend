package com.omniretail.backend.purchasing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.omniretail.backend.TestcontainersConfiguration;
import com.omniretail.backend.administration.entity.User;
import com.omniretail.backend.administration.entity.UserType;
import com.omniretail.backend.auth.entity.Session;
import com.omniretail.backend.auth.service.JwtService;
import com.omniretail.backend.auth.service.SessionService;
import com.omniretail.backend.catalog.repository.ProductRepository;
import com.omniretail.backend.catalog.repository.UnitRepository;
import com.omniretail.backend.purchasing.repository.SupplierCostTierRepository;
import com.omniretail.backend.purchasing.repository.SupplierProductRepository;
import com.omniretail.backend.shared.security.PermissionResolver;
import com.omniretail.backend.shared.security.SaasCapability;
import com.omniretail.backend.shared.security.TenantEntitlementResolver;
import com.omniretail.backend.shared.security.TenantEntitlements;
import java.time.Instant;
import java.util.EnumSet;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(TestcontainersConfiguration.class)
class PurchaseOrderControllerIntegrationTest {

    private static final String BASE = "/api/v1/purchasing/orders";

    @Autowired private MockMvc mvc;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private JwtService jwtService;
    @MockitoBean private SessionService sessions;
    @MockitoSpyBean private SupplierProductRepository supplierProducts;
    @MockitoSpyBean private ProductRepository products;
    @MockitoSpyBean private UnitRepository units;
    @MockitoSpyBean private SupplierCostTierRepository costTiers;
    @MockitoBean private PermissionResolver permissions;
    @MockitoBean private TenantEntitlementResolver entitlements;

    @BeforeEach
    void setUp() {
        given(sessions.isActive(any(), any())).willReturn(true);
        given(permissions.hasPermission(any(), any(), anyString())).willReturn(true);
        given(entitlements.resolve(any())).willReturn(
                new TenantEntitlements(true, true, EnumSet.allOf(SaasCapability.class)));
    }

    @Test
    void authenticationReadPermissionAndMutationCapabilityAreEnforcedButHistoricalReadSurvivesDowngrade()
            throws Exception {
        mvc.perform(get(BASE)).andExpect(status().isUnauthorized());
        Fixture fixture = fixture();
        String token = token(fixture.actor());

        given(permissions.hasPermission(any(), any(), anyString())).willReturn(false);
        mvc.perform(get(BASE).header("Authorization", token))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("ACCESS_DENIED"));

        given(permissions.hasPermission(any(), any(), eq("purchasing.orders.create"))).willReturn(true);
        UUID orderId = createOrder(fixture, token, fixture.branch(), fixture.supplier(), "[]");
        given(entitlements.resolve(fixture.tenant())).willReturn(
                new TenantEntitlements(true, true, EnumSet.of(SaasCapability.inventory)));

        mvc.perform(post(BASE)
                        .header("Authorization", token)
                        .contentType(APPLICATION_JSON)
                        .content(orderBody(fixture.branch(), fixture.supplier(), "[]")))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("CAPABILITY_REQUIRED"));
        mvc.perform(get(BASE + "/" + orderId).header("Authorization", token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(orderId.toString()));
    }

    @Test
    void createsEmptyDraftAndUsesIndependentSequentialPurchaseOrderNumbers() throws Exception {
        Fixture fixture = fixture();
        String token = token(fixture.actor());

        String firstJson = mvc.perform(post(BASE)
                        .header("Authorization", token)
                        .contentType(APPLICATION_JSON)
                        .content(orderBody(fixture.branch(), fixture.supplier(), "[]")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.number").value("OC-001"))
                .andExpect(jsonPath("$.status").value("draft"))
                .andExpect(jsonPath("$.subtotal").value(0))
                .andExpect(jsonPath("$.items.length()").value(0))
                .andReturn()
                .getResponse()
                .getContentAsString();
        int idStart = firstJson.indexOf("\"id\":\"") + 6;
        UUID firstId = UUID.fromString(firstJson.substring(idStart, firstJson.indexOf('"', idStart)));
        mvc.perform(post(BASE + "/" + firstId + "/submit").header("Authorization", token))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("PURCHASE_ORDER_ITEMS_REQUIRED"));
        mvc.perform(post(BASE)
                        .header("Authorization", token)
                        .contentType(APPLICATION_JSON)
                        .content(orderBody(fixture.branch(), fixture.supplier(), "[]")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.number").value("OC-002"));
    }

    @Test
    void resolvesSupplierProductUnitAndFactorServerSideAndPreservesAgreedCost() throws Exception {
        Fixture fixture = fixture();
        String token = token(fixture.actor());
        String items = item(fixture.product(), "2", "12.00")
                .replace("}", ",\"supplierProductId\":\"" + UUID.randomUUID()
                        + "\",\"unitId\":\"" + fixture.baseUnit()
                        + "\",\"purchaseToBaseFactor\":999}");
        long movementsBefore = countInventoryMovements(fixture.tenant());

        mvc.perform(post(BASE)
                        .header("Authorization", token)
                        .contentType(APPLICATION_JSON)
                        .content(orderBody(fixture.branch(), fixture.supplier(), "[" + items + "]")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.items[0].supplierProductId").value(fixture.supplierProduct().toString()))
                .andExpect(jsonPath("$.items[0].unitId").value(fixture.purchaseUnit().toString()))
                .andExpect(jsonPath("$.items[0].purchaseToBaseFactor").value(12))
                .andExpect(jsonPath("$.items[0].unitCost").value(12.00))
                .andExpect(jsonPath("$.items[0].suggestedUnitCost").value(8.00))
                .andExpect(jsonPath("$.items[0].subtotal").value(24.00))
                .andExpect(jsonPath("$.total").value(24.00));
        assertThat(countInventoryMovements(fixture.tenant())).isEqualTo(movementsBefore);
    }

    @Test
    void suggestedCostFallsBackToLastCostWhenNoTierApplies() throws Exception {
        Fixture fixture = fixture();
        jdbc.update(
                "DELETE FROM supplier_cost_tiers WHERE supplier_product_id = ? AND min_quantity = 1",
                fixture.supplierProduct());

        mvc.perform(post(BASE)
                        .header("Authorization", token(fixture.actor()))
                        .contentType(APPLICATION_JSON)
                        .content(orderBody(
                                fixture.branch(),
                                fixture.supplier(),
                                "[" + item(fixture.product(), "2", "13") + "]")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.items[0].suggestedUnitCost").value(10.00))
                .andExpect(jsonPath("$.items[0].unitCost").value(13.00));
    }

    @Test
    void draftAllowsQuantityBelowMoqButSubmitRejectsIt() throws Exception {
        Fixture fixture = fixture();
        String token = token(fixture.actor());
        UUID orderId = createOrder(
                fixture,
                token,
                fixture.branch(),
                fixture.supplier(),
                "[" + item(fixture.product(), "1", "5") + "]");

        mvc.perform(post(BASE + "/" + orderId + "/submit").header("Authorization", token))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("PURCHASE_ORDER_MOQ_NOT_MET"));
        mvc.perform(get(BASE + "/" + orderId).header("Authorization", token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("draft"));
    }

    @Test
    void submitRevalidatesCurrentPackagingPolicyAndActiveSupplierProduct() throws Exception {
        Fixture packagingFixture = fixture();
        String packagingToken = token(packagingFixture.actor());
        UUID packagingOrder = createOrder(
                packagingFixture,
                packagingToken,
                packagingFixture.branch(),
                packagingFixture.supplier(),
                "[" + item(packagingFixture.product(), "2", "5") + "]");
        jdbc.update(
                "UPDATE business_capabilities_configs SET supports_units_and_packaging = false WHERE tenant_id = ?",
                packagingFixture.tenant());
        mvc.perform(post(BASE + "/" + packagingOrder + "/submit")
                        .header("Authorization", packagingToken))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("BUSINESS_CAPABILITY_DISABLED"));

        Fixture inactiveFixture = fixture();
        String inactiveToken = token(inactiveFixture.actor());
        UUID inactiveOrder = createOrder(
                inactiveFixture,
                inactiveToken,
                inactiveFixture.branch(),
                inactiveFixture.supplier(),
                "[" + item(inactiveFixture.product(), "2", "5") + "]");
        jdbc.update("UPDATE supplier_products SET active = false WHERE id = ?", inactiveFixture.supplierProduct());
        mvc.perform(post(BASE + "/" + inactiveOrder + "/submit")
                        .header("Authorization", inactiveToken))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("PURCHASE_ORDER_SUPPLIER_PRODUCT_INACTIVE"));
    }

    @Test
    void submitRefreshesSnapshotsAndApproveDoesNotRevalidateArchivedMasters() throws Exception {
        Fixture fixture = fixture();
        String token = token(fixture.actor());
        UUID orderId = createOrder(
                fixture,
                token,
                fixture.branch(),
                fixture.supplier(),
                "[" + item(fixture.product(), "2", "11") + "]");
        jdbc.update("UPDATE suppliers SET name = 'Proveedor submit' WHERE id = ?", fixture.supplier());
        jdbc.update("UPDATE products SET name = 'Producto submit', sku = 'SKU-SUBMIT' WHERE id = ?", fixture.product());
        jdbc.update("UPDATE units SET symbol = 'bx' WHERE id = ?", fixture.purchaseUnit());
        jdbc.update(
                "UPDATE supplier_products SET supplier_sku = 'SUP-SUBMIT', purchase_to_base_factor = 24 WHERE id = ?",
                fixture.supplierProduct());

        mvc.perform(post(BASE + "/" + orderId + "/submit").header("Authorization", token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("pending_approval"))
                .andExpect(jsonPath("$.supplierName").value("Proveedor submit"))
                .andExpect(jsonPath("$.items[0].productName").value("Producto submit"))
                .andExpect(jsonPath("$.items[0].productSku").value("SKU-SUBMIT"))
                .andExpect(jsonPath("$.items[0].supplierSku").value("SUP-SUBMIT"))
                .andExpect(jsonPath("$.items[0].unitSymbol").value("bx"))
                .andExpect(jsonPath("$.items[0].purchaseToBaseFactor").value(24))
                .andExpect(jsonPath("$.items[0].unitCost").value(11));

        jdbc.update("UPDATE suppliers SET status = 'archived' WHERE id = ?", fixture.supplier());
        jdbc.update("UPDATE products SET status = 'archived' WHERE id = ?", fixture.product());
        jdbc.update("UPDATE units SET status = 'archived' WHERE id = ?", fixture.purchaseUnit());
        jdbc.update("UPDATE supplier_products SET active = false WHERE id = ?", fixture.supplierProduct());
        mvc.perform(post(BASE + "/" + orderId + "/approve").header("Authorization", token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("approved"))
                .andExpect(jsonPath("$.approvedByUserId").value(fixture.actor().user().toString()))
                .andExpect(jsonPath("$.approvedAt").isNotEmpty())
                .andExpect(jsonPath("$.items[0].productName").value("Producto submit"));
    }

    @Test
    void updateIsAtomicAndSupplierChangeUsesOnlyTheNewSupplierProducts() throws Exception {
        Fixture fixture = fixture();
        String token = token(fixture.actor());
        UUID orderId = createOrder(
                fixture,
                token,
                fixture.branch(),
                fixture.supplier(),
                "[" + item(fixture.product(), "2", "10") + "]");

        mvc.perform(put(BASE + "/" + orderId)
                        .header("Authorization", token)
                        .contentType(APPLICATION_JSON)
                        .content(orderBody(
                                fixture.branch(),
                                fixture.supplier(),
                                "[" + item(UUID.randomUUID(), "2", "3") + "]")))
                .andExpect(status().isNotFound());
        mvc.perform(get(BASE + "/" + orderId).header("Authorization", token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.items[0].productId").value(fixture.product().toString()))
                .andExpect(jsonPath("$.items[0].unitCost").value(10));

        UUID secondSupplier = addSupplier(fixture.tenant(), "Proveedor nuevo");
        UUID secondProduct = addProduct(fixture, "SKU-NUEVO");
        addSupplierProduct(fixture, secondSupplier, secondProduct, fixture.purchaseUnit(), "6", "1");
        mvc.perform(put(BASE + "/" + orderId)
                        .header("Authorization", token)
                        .contentType(APPLICATION_JSON)
                        .content(orderBody(
                                fixture.branch(),
                                secondSupplier,
                                "[" + item(secondProduct, "3", "4") + "]")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.supplierId").value(secondSupplier.toString()))
                .andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.items[0].productId").value(secondProduct.toString()))
                .andExpect(jsonPath("$.total").value(12));
    }

    @Test
    void branchScopeIsAppliedBeforePaginationAndDetailDistinguishesForbiddenFromCrossTenant() throws Exception {
        Fixture fixture = fixture();
        UUID secondBranch = addBranch(fixture.tenant(), "B2");
        String globalToken = token(fixture.actor());
        UUID allowedOrder = createOrder(fixture, globalToken, fixture.branch(), fixture.supplier(), "[]");
        UUID hiddenOrder = createOrder(fixture, globalToken, secondBranch, fixture.supplier(), "[]");
        Actor limited = addActor(fixture.tenant(), fixture.branch(), false);
        String limitedToken = token(limited);

        mvc.perform(get(BASE).header("Authorization", limitedToken).param("size", "1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalItems").value(1))
                .andExpect(jsonPath("$.items[0].id").value(allowedOrder.toString()));
        mvc.perform(get(BASE + "/" + hiddenOrder).header("Authorization", limitedToken))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("BRANCH_ACCESS_DENIED"));

        Fixture other = fixture();
        mvc.perform(get(BASE + "/" + allowedOrder).header("Authorization", token(other.actor())))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("PURCHASE_ORDER_NOT_FOUND"));
    }

    @Test
    void cancelUsesStatusDependentPermissionsAndRejectsInvalidTransitions() throws Exception {
        Fixture fixture = fixture();
        String token = token(fixture.actor());
        UUID draft = createOrder(fixture, token, fixture.branch(), fixture.supplier(), "[]");
        given(permissions.hasPermission(any(), any(), anyString())).willReturn(false);
        given(permissions.hasPermission(any(), any(), eq("purchasing.orders.approve"))).willReturn(true);
        mvc.perform(post(BASE + "/" + draft + "/cancel")
                        .header("Authorization", token)
                        .contentType(APPLICATION_JSON)
                        .content("{\"reason\":\"Compra ya no requerida\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("cancelled"))
                .andExpect(jsonPath("$.cancellationReason").value("Compra ya no requerida"));
        mvc.perform(post(BASE + "/" + draft + "/cancel")
                        .header("Authorization", token)
                        .contentType(APPLICATION_JSON)
                        .content("{\"reason\":\"Otra vez\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("PURCHASE_ORDER_INVALID_STATUS"));
    }

    @Test
    void createApproveAndApprovedCancellationUseTheirRequiredPermissions() throws Exception {
        Fixture fixture = fixture();
        String token = token(fixture.actor());
        given(permissions.hasPermission(any(), any(), eq("purchasing.orders.create"))).willReturn(false);
        mvc.perform(post(BASE)
                        .header("Authorization", token)
                        .contentType(APPLICATION_JSON)
                        .content(orderBody(fixture.branch(), fixture.supplier(), "[]")))
                .andExpect(status().isForbidden());

        given(permissions.hasPermission(any(), any(), eq("purchasing.orders.create"))).willReturn(true);
        UUID orderId = createOrder(
                fixture,
                token,
                fixture.branch(),
                fixture.supplier(),
                "[" + item(fixture.product(), "2", "10") + "]");
        mvc.perform(post(BASE + "/" + orderId + "/submit").header("Authorization", token))
                .andExpect(status().isOk());

        given(permissions.hasPermission(any(), any(), anyString())).willReturn(false);
        given(permissions.hasPermission(any(), any(), eq("purchasing.orders.create"))).willReturn(true);
        mvc.perform(post(BASE + "/" + orderId + "/approve").header("Authorization", token))
                .andExpect(status().isForbidden());
        given(permissions.hasPermission(any(), any(), eq("purchasing.orders.approve"))).willReturn(true);
        mvc.perform(post(BASE + "/" + orderId + "/approve").header("Authorization", token))
                .andExpect(status().isOk());

        given(permissions.hasPermission(any(), any(), anyString())).willReturn(false);
        given(permissions.hasPermission(any(), any(), eq("purchasing.orders.create"))).willReturn(true);
        mvc.perform(post(BASE + "/" + orderId + "/cancel")
                        .header("Authorization", token)
                        .contentType(APPLICATION_JSON)
                        .content("{\"reason\":\"Intento sin approve\"}"))
                .andExpect(status().isForbidden());
    }

    @Test
    void invalidDecimalBaseQuantityAndInactiveNewSupplierProductAreRejectedInDraft() throws Exception {
        Fixture fixture = fixture();
        String token = token(fixture.actor());
        jdbc.update("UPDATE units SET allows_decimals = true WHERE id = ?", fixture.purchaseUnit());
        jdbc.update("UPDATE supplier_products SET purchase_to_base_factor = 3 WHERE id = ?", fixture.supplierProduct());
        mvc.perform(post(BASE)
                        .header("Authorization", token)
                        .contentType(APPLICATION_JSON)
                        .content(orderBody(
                                fixture.branch(),
                                fixture.supplier(),
                                "[" + item(fixture.product(), "0.5", "2") + "]")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("PURCHASE_ORDER_INVALID_QUANTITY"));

        jdbc.update("UPDATE supplier_products SET active = false WHERE id = ?", fixture.supplierProduct());
        mvc.perform(post(BASE)
                        .header("Authorization", token)
                        .contentType(APPLICATION_JSON)
                        .content(orderBody(
                                fixture.branch(),
                                fixture.supplier(),
                                "[" + item(fixture.product(), "2", "2") + "]")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("PURCHASE_ORDER_SUPPLIER_PRODUCT_INACTIVE"));
    }

    @Test
    void pessimisticLockAllowsOnlyOneConcurrentSubmitTransition() throws Exception {
        Fixture fixture = fixture();
        String token = token(fixture.actor());
        UUID orderId = createOrder(
                fixture,
                token,
                fixture.branch(),
                fixture.supplier(),
                "[" + item(fixture.product(), "2", "10") + "]");

        assertThat(concurrently(() -> mvc.perform(post(BASE + "/" + orderId + "/submit")
                                .header("Authorization", token))
                        .andReturn()
                        .getResponse()
                        .getStatus()))
                .containsExactlyInAnyOrder(200, 409);
    }

    private List<Integer> concurrently(Callable<Integer> call) throws Exception {
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            Callable<Integer> task = () -> {
                ready.countDown();
                if (!start.await(10, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("Inicio concurrente agotado.");
                }
                return call.call();
            };
            var first = executor.submit(task);
            var second = executor.submit(task);
            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            try {
                return List.of(first.get(30, TimeUnit.SECONDS), second.get(30, TimeUnit.SECONDS));
            } finally {
                start.countDown();
                executor.shutdownNow();
            }
        }
    }

    @Test
    void draftResolutionLoadsProductsSupplierProductsUnitsAndTiersInBatchInsteadOfPerLine() throws Exception {
        Fixture fixture = fixture();
        String token = token(fixture.actor());
        UUID second = addProduct(fixture, "SKU-B-");
        UUID third = addProduct(fixture, "SKU-C-");
        UUID secondRelation = addSupplierProduct(fixture, fixture.supplier(), second, fixture.purchaseUnit(), "12", "2");
        addSupplierProduct(fixture, fixture.supplier(), third, fixture.purchaseUnit(), "12", "2");
        jdbc.update("""
                INSERT INTO supplier_cost_tiers (tenant_id, supplier_product_id, min_quantity, unit_cost)
                VALUES (?, ?, 1, 6.00), (?, ?, 5, 5.00)
                """, fixture.tenant(), secondRelation, fixture.tenant(), secondRelation);
        clearInvocations(supplierProducts, products, units, costTiers);

        mvc.perform(post(BASE)
                        .header("Authorization", token)
                        .contentType(APPLICATION_JSON)
                        .content(orderBody(fixture.branch(), fixture.supplier(), "["
                                + item(fixture.product(), "10", "7.00") + ","
                                + item(second, "5", "5.00") + ","
                                + item(third, "2", "9.00") + "]")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.items.length()").value(3))
                .andExpect(jsonPath("$.items[0].productId").value(fixture.product().toString()))
                .andExpect(jsonPath("$.items[1].productId").value(second.toString()))
                .andExpect(jsonPath("$.items[2].productId").value(third.toString()));

        verify(supplierProducts, times(1)).findByTenantIdAndSupplierIdAndProductIdIn(any(), any(), any());
        verify(supplierProducts, never()).findByTenantIdAndSupplierIdAndProductId(any(), any(), any());
        verify(products, times(1)).findByTenantIdAndIdIn(any(), any());
        verify(products, never()).findByTenantIdAndId(any(), any());
        verify(units, times(1)).findByTenantIdAndIdIn(any(), any());
        verify(units, never()).findByTenantIdAndId(any(), any());
        verify(costTiers, times(1)).findByTenantIdAndSupplierProductIdInOrderByMinQuantityAsc(any(), any());
        verify(costTiers, never()).findByTenantIdAndSupplierProductIdOrderByMinQuantityAsc(any(), any());
    }

    @Test
    void draftResolutionKeepsErrorsForUnknownProductAndMissingSupplierProductAndTenantIsolation() throws Exception {
        Fixture fixture = fixture();
        String token = token(fixture.actor());
        UUID noRelation = addProduct(fixture, "SKU-NR-");
        Fixture other = fixture();
        UUID foreignProduct = other.product();

        for (UUID product : List.of(noRelation, foreignProduct, UUID.randomUUID())) {
            mvc.perform(post(BASE)
                            .header("Authorization", token)
                            .contentType(APPLICATION_JSON)
                            .content(orderBody(fixture.branch(), fixture.supplier(),
                                    "[" + item(fixture.product(), "10", "7.00") + "," + item(product, "2", "9.00") + "]")))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.code").value("PURCHASE_ORDER_SUPPLIER_PRODUCT_NOT_FOUND"));
        }
        assertThat(jdbc.queryForObject(
                        "SELECT count(*) FROM purchase_orders WHERE tenant_id = ?", Long.class, fixture.tenant()))
                .isZero();
    }

    private UUID createOrder(
            Fixture fixture, String token, UUID branchId, UUID supplierId, String items) throws Exception {
        String json = mvc.perform(post(BASE)
                        .header("Authorization", token)
                        .contentType(APPLICATION_JSON)
                        .content(orderBody(branchId, supplierId, items)))
                .andExpect(status().isCreated())
                .andReturn()
                .getResponse()
                .getContentAsString();
        int valueStart = json.indexOf("\"id\":\"") + 6;
        return UUID.fromString(json.substring(valueStart, json.indexOf('"', valueStart)));
    }

    private Fixture fixture() {
        UUID tenant = UUID.randomUUID();
        UUID branch = addTenantAndBranch(tenant);
        Actor actor = addActor(tenant, branch, true);
        UUID category = UUID.randomUUID();
        UUID baseUnit = UUID.randomUUID();
        UUID purchaseUnit = UUID.randomUUID();
        UUID product = UUID.randomUUID();
        UUID supplier = UUID.randomUUID();
        String suffix = tenant.toString();
        jdbc.update(
                "INSERT INTO business_capabilities_configs (tenant_id, supports_units_and_packaging) VALUES (?, true)",
                tenant);
        jdbc.update(
                "INSERT INTO categories (id, tenant_id, name, slug) VALUES (?, ?, 'Categoria', ?)",
                category,
                tenant,
                "cat-" + suffix);
        jdbc.update("""
                INSERT INTO units (id, tenant_id, code, name, symbol, category, allows_decimals, status)
                VALUES (?, ?, ?, 'Unidad', 'u', 'unit', false, 'active'),
                       (?, ?, ?, 'Caja', 'cj', 'unit', false, 'active')
                """, baseUnit, tenant, "U-" + suffix.substring(0, 8),
                purchaseUnit, tenant, "C-" + suffix.substring(0, 8));
        jdbc.update("""
                INSERT INTO products
                    (id, tenant_id, sku, name, product_type, category_id, base_unit_id, tracking_stock)
                VALUES (?, ?, ?, 'Servicio comprable', 'service', ?, ?, false)
                """, product, tenant, "SKU-" + suffix, category, baseUnit);
        jdbc.update(
                "INSERT INTO suppliers (id, tenant_id, name, status) VALUES (?, ?, 'Proveedor', 'active')",
                supplier,
                tenant);
        UUID supplierProduct = addSupplierProduct(
                new Fixture(tenant, branch, actor, category, baseUnit, purchaseUnit, product, supplier, null),
                supplier,
                product,
                purchaseUnit,
                "12",
                "2");
        jdbc.update("""
                INSERT INTO supplier_cost_tiers (tenant_id, supplier_product_id, min_quantity, unit_cost)
                VALUES (?, ?, 1, 8.00), (?, ?, 10, 7.00)
                """, tenant, supplierProduct, tenant, supplierProduct);
        return new Fixture(
                tenant, branch, actor, category, baseUnit, purchaseUnit, product, supplier, supplierProduct);
    }

    private UUID addTenantAndBranch(UUID tenant) {
        UUID branch = UUID.randomUUID();
        jdbc.update("INSERT INTO tenants (id, name, slug) VALUES (?, 'PO test', ?)", tenant, "po-" + tenant);
        jdbc.update("""
                INSERT INTO branches (id, tenant_id, code, name, type, status)
                VALUES (?, ?, 'B1', 'Principal', 'main', 'active')
                """, branch, tenant);
        return branch;
    }

    private UUID addBranch(UUID tenant, String code) {
        UUID branch = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO branches (id, tenant_id, code, name, type, status)
                VALUES (?, ?, ?, ?, 'store', 'active')
                """, branch, tenant, code + branch.toString().substring(0, 4), "Sucursal " + code);
        return branch;
    }

    private Actor addActor(UUID tenant, UUID branch, boolean allBranches) {
        UUID role = UUID.randomUUID();
        UUID user = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO roles (id, tenant_id, name, branch_scope, permissions)
                VALUES (?, ?, ?, ?, '{}')
                """, role, tenant, "Rol " + role, allBranches ? "all" : "selected");
        if (allBranches) {
            jdbc.update("""
                    INSERT INTO users (id, tenant_id, name, email, type, status, role_id, branch_id)
                    VALUES (?, ?, 'Comprador', ?, 'employee', 'active', ?, ?)
                    """, user, tenant, user + "@test.local", role, branch);
        } else {
            jdbc.update("""
                    INSERT INTO users
                        (id, tenant_id, name, email, type, status, role_id, branch_id, allowed_branch_ids)
                    VALUES (?, ?, 'Comprador limitado', ?, 'employee', 'active', ?, ?, ARRAY[CAST(? AS UUID)])
                    """, user, tenant, user + "@test.local", role, branch, branch.toString());
        }
        return new Actor(tenant, user, role, branch);
    }

    private UUID addSupplier(UUID tenant, String name) {
        UUID supplier = UUID.randomUUID();
        jdbc.update(
                "INSERT INTO suppliers (id, tenant_id, name, status) VALUES (?, ?, ?, 'active')",
                supplier,
                tenant,
                name);
        return supplier;
    }

    private UUID addProduct(Fixture fixture, String sku) {
        UUID product = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO products (id, tenant_id, sku, name, category_id, base_unit_id)
                VALUES (?, ?, ?, 'Producto nuevo', ?, ?)
                """, product, fixture.tenant(), sku + product, fixture.category(), fixture.baseUnit());
        return product;
    }

    private UUID addSupplierProduct(
            Fixture fixture, UUID supplier, UUID product, UUID unit, String factor, String moq) {
        UUID supplierProduct = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO supplier_products
                    (id, tenant_id, supplier_id, product_id, supplier_sku, purchase_unit_id,
                     purchase_to_base_factor, last_cost, lead_time_days, minimum_order_quantity)
                VALUES (?, ?, ?, ?, 'SUP-SKU', ?, ?::numeric, 10.00, 2, ?::numeric)
                """, supplierProduct, fixture.tenant(), supplier, product, unit, factor, moq);
        return supplierProduct;
    }

    private String token(Actor actor) {
        User user = User.builder()
                .name("Comprador")
                .email(actor.user() + "@test.local")
                .type(UserType.employee)
                .roleId(actor.role())
                .branchId(actor.branch())
                .build();
        user.setTenantId(actor.tenant());
        ReflectionTestUtils.setField(user, "id", actor.user());
        Session session = Session.builder()
                .id(UUID.randomUUID())
                .userId(actor.user())
                .expiresAt(Instant.now().plusSeconds(3600))
                .build();
        return "Bearer " + jwtService.generateToken(user, session);
    }

    private long countInventoryMovements(UUID tenantId) {
        return jdbc.queryForObject(
                "SELECT count(*) FROM inventory_movements WHERE tenant_id = ?", Long.class, tenantId);
    }

    private static String orderBody(UUID branch, UUID supplier, String items) {
        return """
                {"branchId":"%s","supplierId":"%s","expectedDate":"2030-01-15",
                 "notes":"Orden de prueba","items":%s}
                """.formatted(branch, supplier, items);
    }

    private static String item(UUID product, String quantity, String unitCost) {
        return """
                {"productId":"%s","quantity":%s,"unitCost":%s}
                """.formatted(product, quantity, unitCost).trim();
    }

    private record Actor(UUID tenant, UUID user, UUID role, UUID branch) {}

    private record Fixture(
            UUID tenant,
            UUID branch,
            Actor actor,
            UUID category,
            UUID baseUnit,
            UUID purchaseUnit,
            UUID product,
            UUID supplier,
            UUID supplierProduct) {}
}
