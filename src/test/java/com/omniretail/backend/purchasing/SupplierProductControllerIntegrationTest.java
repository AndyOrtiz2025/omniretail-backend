package com.omniretail.backend.purchasing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
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
import com.omniretail.backend.shared.security.PermissionResolver;
import com.omniretail.backend.shared.security.SaasCapability;
import com.omniretail.backend.shared.security.TenantEntitlementResolver;
import com.omniretail.backend.shared.security.TenantEntitlements;
import java.time.Instant;
import java.util.EnumSet;
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

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(TestcontainersConfiguration.class)
class SupplierProductControllerIntegrationTest {

    private static final String BASE = "/api/v1/purchasing/supplier-products";

    @Autowired private MockMvc mvc;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private JwtService jwtService;
    @MockitoBean private SessionService sessions;
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
    void requiresAuthenticationManagePermissionAndPurchasingCapability() throws Exception {
        mvc.perform(get(BASE)).andExpect(status().isUnauthorized());

        Fixture fixture = fixture(false, false);
        given(permissions.hasPermission(any(), any(), eq("admin.suppliers.manage"))).willReturn(false);
        mvc.perform(get(BASE).header("Authorization", token(fixture)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("ACCESS_DENIED"));

        given(permissions.hasPermission(any(), any(), eq("admin.suppliers.manage"))).willReturn(true);
        given(entitlements.resolve(fixture.tenant())).willReturn(
                new TenantEntitlements(true, true, EnumSet.of(SaasCapability.inventory)));
        mvc.perform(get(BASE).header("Authorization", token(fixture)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("CAPABILITY_REQUIRED"));
    }

    @Test
    void createsServiceProductAndDefaultsPreferredFalseWithNullableSku() throws Exception {
        Fixture fixture = fixture(false, true);

        mvc.perform(post(BASE)
                        .header("Authorization", token(fixture))
                        .contentType(APPLICATION_JSON)
                        .content(createBody(fixture, fixture.supplier(), fixture.product(), fixture.baseUnit(), "1", null)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.tenantId").value(fixture.tenant().toString()))
                .andExpect(jsonPath("$.supplierSku").doesNotExist())
                .andExpect(jsonPath("$.preferred").value(false))
                .andExpect(jsonPath("$.active").value(true));
    }

    @Test
    void enforcesTenantAndMasterAvailability() throws Exception {
        Fixture fixture = fixture(false, false);
        Fixture other = fixture(false, false);

        mvc.perform(post(BASE)
                        .header("Authorization", token(fixture))
                        .contentType(APPLICATION_JSON)
                        .content(createBody(fixture, other.supplier(), fixture.product(), fixture.baseUnit(), "1", false)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("SUPPLIER_NOT_FOUND"));

        jdbc.update("UPDATE suppliers SET status = 'inactive' WHERE id = ?", fixture.supplier());
        mvc.perform(post(BASE)
                        .header("Authorization", token(fixture))
                        .contentType(APPLICATION_JSON)
                        .content(createBody(fixture, fixture.supplier(), fixture.product(), fixture.baseUnit(), "1", false)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("SUPPLIER_PRODUCT_SUPPLIER_INACTIVE"));
    }

    @Test
    void validatesFactorAndPackagingPolicy() throws Exception {
        Fixture disabled = fixture(false, false);
        String disabledToken = token(disabled);

        mvc.perform(post(BASE)
                        .header("Authorization", disabledToken)
                        .contentType(APPLICATION_JSON)
                        .content(createBody(disabled, disabled.supplier(), disabled.product(), disabled.baseUnit(), "2", false)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("SUPPLIER_PRODUCT_INVALID_FACTOR"));

        mvc.perform(post(BASE)
                        .header("Authorization", disabledToken)
                        .contentType(APPLICATION_JSON)
                        .content(createBody(disabled, disabled.supplier(), disabled.product(), disabled.altUnit(), "12", false)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("BUSINESS_CAPABILITY_DISABLED"));

        Fixture enabled = fixture(true, false);
        mvc.perform(post(BASE)
                        .header("Authorization", token(enabled))
                        .contentType(APPLICATION_JSON)
                        .content(createBody(enabled, enabled.supplier(), enabled.product(), enabled.altUnit(), "12", false)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.purchaseToBaseFactor").value(12));
    }

    @Test
    void validatesMoneyLeadTimeAndIntegerUnitMoq() throws Exception {
        Fixture fixture = fixture(false, false);
        String token = token(fixture);

        mvc.perform(post(BASE)
                        .header("Authorization", token)
                        .contentType(APPLICATION_JSON)
                        .content(createBody(fixture, fixture.supplier(), fixture.product(), fixture.baseUnit(), "1", false)
                                .replace("\"lastCost\":0", "\"lastCost\":-1")))
                .andExpect(status().isBadRequest());

        mvc.perform(post(BASE)
                        .header("Authorization", token)
                        .contentType(APPLICATION_JSON)
                        .content(createBody(fixture, fixture.supplier(), fixture.product(), fixture.baseUnit(), "1", false)
                                .replace("\"leadTimeDays\":0", "\"leadTimeDays\":-1")))
                .andExpect(status().isBadRequest());

        mvc.perform(post(BASE)
                        .header("Authorization", token)
                        .contentType(APPLICATION_JSON)
                        .content(createBody(fixture, fixture.supplier(), fixture.product(), fixture.baseUnit(), "1", false)
                                .replace("\"minimumOrderQuantity\":1", "\"minimumOrderQuantity\":1.5")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("SUPPLIER_PRODUCT_INVALID_MOQ"));
    }

    @Test
    void duplicateRelationConflictsAndPreferredCanSwitch() throws Exception {
        Fixture fixture = fixture(false, false);
        UUID secondSupplier = insertSupplier(fixture.tenant(), "Segundo", "active");
        String token = token(fixture);
        UUID first = create(fixture, fixture.supplier(), true);

        mvc.perform(post(BASE)
                        .header("Authorization", token)
                        .contentType(APPLICATION_JSON)
                        .content(createBody(fixture, fixture.supplier(), fixture.product(), fixture.baseUnit(), "1", false)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("SUPPLIER_PRODUCT_CONFLICT"));

        UUID second = create(fixture, secondSupplier, false);
        mvc.perform(post(BASE + "/" + second + "/preferred").header("Authorization", token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.preferred").value(true));
        assertThat(jdbc.queryForObject(
                        "SELECT preferred FROM supplier_products WHERE id = ?", Boolean.class, first))
                .isFalse();
    }

    @Test
    void archiveIsIdempotentClearsPreferredPreservesTiersAndReactivateDoesNotPrefer() throws Exception {
        Fixture fixture = fixture(false, false);
        UUID id = create(fixture, fixture.supplier(), true);
        String token = token(fixture);
        replaceTiers(token, id, """
                {"tiers":[{"minQuantity":1,"unitCost":9.50}]}
                """);

        mvc.perform(delete(BASE + "/" + id).header("Authorization", token)).andExpect(status().isNoContent());
        mvc.perform(delete(BASE + "/" + id).header("Authorization", token)).andExpect(status().isNoContent());
        mvc.perform(post(BASE + "/" + id + "/preferred").header("Authorization", token))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("SUPPLIER_PRODUCT_INACTIVE"));
        assertThat(jdbc.queryForObject(
                        "SELECT count(*) FROM supplier_cost_tiers WHERE supplier_product_id = ?", Long.class, id))
                .isOne();

        mvc.perform(post(BASE + "/" + id + "/reactivate").header("Authorization", token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.active").value(true))
                .andExpect(jsonPath("$.preferred").value(false))
                .andExpect(jsonPath("$.costTiers.length()").value(1));
    }

    @Test
    void updatesCommercialFieldsButRejectsArchivedRelationships() throws Exception {
        Fixture fixture = fixture(true, false);
        UUID id = create(fixture, fixture.supplier(), false);
        String token = token(fixture);
        String update = """
                {"supplierSku":" CAJA-24 ","purchaseUnitId":"%s","purchaseToBaseFactor":24,
                 "lastCost":120.50,"leadTimeDays":3,"minimumOrderQuantity":2}
                """.formatted(fixture.altUnit());

        mvc.perform(put(BASE + "/" + id)
                        .header("Authorization", token)
                        .contentType(APPLICATION_JSON)
                        .content(update))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.supplierSku").value("CAJA-24"))
                .andExpect(jsonPath("$.purchaseUnitId").value(fixture.altUnit().toString()))
                .andExpect(jsonPath("$.purchaseToBaseFactor").value(24))
                .andExpect(jsonPath("$.lastCost").value(120.50))
                .andExpect(jsonPath("$.leadTimeDays").value(3));

        mvc.perform(delete(BASE + "/" + id).header("Authorization", token)).andExpect(status().isNoContent());
        mvc.perform(put(BASE + "/" + id)
                        .header("Authorization", token)
                        .contentType(APPLICATION_JSON)
                        .content(update))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("SUPPLIER_PRODUCT_INACTIVE"));
    }

    @Test
    void costTierReplacementIsOrderedAtomicAndCanBeEmptied() throws Exception {
        Fixture fixture = fixture(false, false);
        UUID id = create(fixture, fixture.supplier(), false);
        String token = token(fixture);

        replaceTiers(token, id, """
                {"tiers":[{"minQuantity":10,"unitCost":8.00},{"minQuantity":1,"unitCost":10.00}]}
                """)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].minQuantity").value(1))
                .andExpect(jsonPath("$[1].minQuantity").value(10));

        replaceTiers(token, id, """
                {"tiers":[{"minQuantity":2,"unitCost":7.00},{"minQuantity":2.000,"unitCost":6.00}]}
                """)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("SUPPLIER_COST_TIER_CONFLICT"));
        assertThat(jdbc.queryForObject(
                        "SELECT count(*) FROM supplier_cost_tiers WHERE supplier_product_id = ?", Long.class, id))
                .isEqualTo(2);

        replaceTiers(token, id, "{\"tiers\":[]}").andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(0));
    }

    @Test
    void quantityDecimalsFollowPurchaseUnitPolicy() throws Exception {
        Fixture integerFixture = fixture(false, false);
        UUID integerProduct = create(integerFixture, integerFixture.supplier(), false);
        replaceTiers(token(integerFixture), integerProduct, "{\"tiers\":[{\"minQuantity\":1.5,\"unitCost\":1}]}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("SUPPLIER_COST_TIER_INVALID"));

        Fixture decimalFixture = fixture(true, false);
        jdbc.update("UPDATE units SET allows_decimals = true WHERE id = ?", decimalFixture.altUnit());
        String body = createBody(
                        decimalFixture,
                        decimalFixture.supplier(),
                        decimalFixture.product(),
                        decimalFixture.altUnit(),
                        "6",
                        false)
                .replace("\"minimumOrderQuantity\":1", "\"minimumOrderQuantity\":1.5");
        mvc.perform(post(BASE)
                        .header("Authorization", token(decimalFixture))
                        .contentType(APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.minimumOrderQuantity").value(1.5));
    }

    @Test
    void reactivationRevalidatesCurrentPackagingPolicy() throws Exception {
        Fixture fixture = fixture(true, false);
        String token = token(fixture);
        String body = createBody(
                fixture, fixture.supplier(), fixture.product(), fixture.altUnit(), "12", false);
        String json = mvc.perform(post(BASE)
                        .header("Authorization", token)
                        .contentType(APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isCreated())
                .andReturn()
                .getResponse()
                .getContentAsString();
        UUID id = UUID.fromString(
                json.substring(json.indexOf("\"id\":\"") + 6, json.indexOf("\"", json.indexOf("\"id\":\"") + 6)));
        mvc.perform(delete(BASE + "/" + id).header("Authorization", token)).andExpect(status().isNoContent());
        jdbc.update(
                "UPDATE business_capabilities_configs SET supports_units_and_packaging = false WHERE tenant_id = ?",
                fixture.tenant());

        mvc.perform(post(BASE + "/" + id + "/reactivate").header("Authorization", token))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("BUSINESS_CAPABILITY_DISABLED"));
    }

    @Test
    void operationalLookupUsesPermissionOrReturnsOnlyActiveAndIncludesTiers() throws Exception {
        Fixture fixture = fixture(false, false);
        UUID active = create(fixture, fixture.supplier(), false);
        UUID archivedSupplier = insertSupplier(fixture.tenant(), "Archivado", "active");
        UUID archived = create(fixture, archivedSupplier, false);
        String token = token(fixture);
        replaceTiers(token, active, "{\"tiers\":[{\"minQuantity\":1,\"unitCost\":0}]}");
        mvc.perform(delete(BASE + "/" + archived).header("Authorization", token)).andExpect(status().isNoContent());

        given(permissions.hasPermission(any(), any(), anyString())).willReturn(false);
        given(permissions.hasPermission(any(), any(), eq("purchasing.orders.create"))).willReturn(true);
        mvc.perform(get(BASE + "/active").header("Authorization", token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].id").value(active.toString()))
                .andExpect(jsonPath("$[0].costTiers[0].unitCost").value(0));

        given(permissions.hasPermission(any(), any(), anyString())).willReturn(false);
        mvc.perform(get(BASE + "/active").header("Authorization", token))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("ACCESS_DENIED"));
    }

    @Test
    void adminListFiltersAtDatabaseAndDetailIsTenantScoped() throws Exception {
        Fixture fixture = fixture(false, false);
        Fixture other = fixture(false, false);
        UUID id = create(fixture, fixture.supplier(), false);
        create(other, other.supplier(), false);

        mvc.perform(get(BASE)
                        .header("Authorization", token(fixture))
                        .param("supplierId", fixture.supplier().toString())
                        .param("active", "true"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalItems").value(1))
                .andExpect(jsonPath("$.items[0].id").value(id.toString()))
                .andExpect(jsonPath("$.pageSize").value(20));

        mvc.perform(get(BASE + "/" + id).header("Authorization", token(other)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("SUPPLIER_PRODUCT_NOT_FOUND"));
    }

    private UUID create(Fixture fixture, UUID supplierId, boolean preferred) throws Exception {
        String json = mvc.perform(post(BASE)
                        .header("Authorization", token(fixture))
                        .contentType(APPLICATION_JSON)
                        .content(createBody(
                                fixture, supplierId, fixture.product(), fixture.baseUnit(), "1", preferred)))
                .andExpect(status().isCreated())
                .andReturn()
                .getResponse()
                .getContentAsString();
        return UUID.fromString(json.substring(json.indexOf("\"id\":\"") + 6, json.indexOf("\"", json.indexOf("\"id\":\"") + 6)));
    }

    private org.springframework.test.web.servlet.ResultActions replaceTiers(String token, UUID id, String body)
            throws Exception {
        return mvc.perform(put(BASE + "/" + id + "/cost-tiers")
                .header("Authorization", token)
                .contentType(APPLICATION_JSON)
                .content(body));
    }

    private Fixture fixture(boolean packaging, boolean serviceProduct) {
        UUID tenant = UUID.randomUUID();
        UUID baseUnit = UUID.randomUUID();
        UUID altUnit = UUID.randomUUID();
        UUID category = UUID.randomUUID();
        UUID product = UUID.randomUUID();
        UUID supplier = UUID.randomUUID();
        UUID user = UUID.randomUUID();
        UUID role = UUID.randomUUID();
        String suffix = tenant.toString();
        jdbc.update("INSERT INTO tenants (id, name, slug) VALUES (?, 'Purchasing test', ?)", tenant, "purch-" + suffix);
        jdbc.update(
                "INSERT INTO business_capabilities_configs (tenant_id, supports_units_and_packaging) VALUES (?, ?)",
                tenant,
                packaging);
        jdbc.update(
                "INSERT INTO categories (id, tenant_id, name, slug) VALUES (?, ?, 'Categoria', ?)",
                category,
                tenant,
                "cat-" + suffix);
        jdbc.update("""
                INSERT INTO units (id, tenant_id, code, name, symbol, category, allows_decimals, status)
                VALUES (?, ?, ?, 'Unidad', 'u', 'unit', false, 'active')
                """, baseUnit, tenant, "U-" + suffix.substring(0, 8));
        jdbc.update("""
                INSERT INTO units (id, tenant_id, code, name, symbol, category, allows_decimals, status)
                VALUES (?, ?, ?, 'Caja', 'cj', 'unit', false, 'active')
                """, altUnit, tenant, "C-" + suffix.substring(0, 8));
        jdbc.update("""
                INSERT INTO products (id, tenant_id, sku, name, product_type, category_id, base_unit_id)
                VALUES (?, ?, ?, 'Producto', ?, ?, ?)
                """, product, tenant, "SKU-" + suffix, serviceProduct ? "service" : "physical", category, baseUnit);
        jdbc.update("""
                INSERT INTO suppliers (id, tenant_id, name, status) VALUES (?, ?, 'Proveedor', 'active')
                """, supplier, tenant);
        jdbc.update("""
                INSERT INTO users (id, tenant_id, name, email, type, status)
                VALUES (?, ?, 'Comprador', ?, 'employee', 'active')
                """, user, tenant, user + "@test.local");
        return new Fixture(tenant, baseUnit, altUnit, product, supplier, user, role);
    }

    private UUID insertSupplier(UUID tenant, String name, String status) {
        UUID id = UUID.randomUUID();
        jdbc.update(
                "INSERT INTO suppliers (id, tenant_id, name, status) VALUES (?, ?, ?, ?)",
                id,
                tenant,
                name + " " + id,
                status);
        return id;
    }

    private String token(Fixture fixture) {
        User user = User.builder()
                .name("Comprador")
                .email("purchasing@test.local")
                .type(UserType.employee)
                .roleId(fixture.role())
                .build();
        user.setTenantId(fixture.tenant());
        ReflectionTestUtils.setField(user, "id", fixture.user());
        Session session = Session.builder()
                .id(UUID.randomUUID())
                .userId(fixture.user())
                .expiresAt(Instant.now().plusSeconds(3600))
                .build();
        return "Bearer " + jwtService.generateToken(user, session);
    }

    private static String createBody(
            Fixture fixture,
            UUID supplier,
            UUID product,
            UUID unit,
            String factor,
            Boolean preferred) {
        String preferredField = preferred == null ? "" : ",\"preferred\":" + preferred;
        return """
                {"supplierId":"%s","productId":"%s","purchaseUnitId":"%s",
                 "purchaseToBaseFactor":%s,"lastCost":0,"leadTimeDays":0,"minimumOrderQuantity":1%s}
                """.formatted(supplier, product, unit, factor, preferredField);
    }

    private record Fixture(
            UUID tenant, UUID baseUnit, UUID altUnit, UUID product, UUID supplier, UUID user, UUID role) {}
}
