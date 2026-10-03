package com.omniretail.backend.inventory.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
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
import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
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

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(TestcontainersConfiguration.class)
class InventoryAdjustmentControllerTest {

    private static final String ADJUSTMENTS = "/api/v1/inventory/adjustments";
    private static final String CREATE_PERMISSION = "inventory.adjustment.create";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JwtService jwtService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @MockitoBean
    private SessionService sessionService;

    @MockitoBean
    private PermissionResolver permissionResolver;

    @MockitoBean
    private TenantEntitlementResolver entitlementResolver;

    @MockitoBean
    private BranchAccessResolver branchAccessResolver;

    @BeforeEach
    void setUp() {
        given(sessionService.isActive(any(), any())).willReturn(true);
        given(permissionResolver.hasPermission(
                        any(UUID.class), any(UUID.class), anyString()))
                .willReturn(true);
        given(entitlementResolver.resolve(any(UUID.class)))
                .willReturn(new TenantEntitlements(
                        true, true, EnumSet.allOf(SaasCapability.class)));
        given(branchAccessResolver.resolve(any()))
                .willReturn(new BranchAccess(true, Set.of()));
    }

    @Test
    void authorizedAdjustmentCreatesMovementAndUpdatesBalance() throws Exception {
        Fixture fixture = createFixture();
        insertBalance(fixture, "10.000", "2.000");
        UUID referenceId = UUID.randomUUID();

        mockMvc.perform(post(ADJUSTMENTS)
                        .header("Authorization", token(fixture))
                        .contentType(APPLICATION_JSON)
                        .content(requestJson(
                                fixture.branchId(),
                                fixture.productId(),
                                "in",
                                "5.000",
                                "Conteo físico",
                                referenceId)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.tenantId").value(fixture.tenantId().toString()))
                .andExpect(jsonPath("$.branchId").value(fixture.branchId().toString()))
                .andExpect(jsonPath("$.productId").value(fixture.productId().toString()))
                .andExpect(jsonPath("$.type").value("in"))
                .andExpect(jsonPath("$.quantity").value(5.000))
                .andExpect(jsonPath("$.quantityBefore").value(10.000))
                .andExpect(jsonPath("$.quantityAfter").value(15.000))
                .andExpect(jsonPath("$.reason").value("Conteo físico"))
                .andExpect(jsonPath("$.referenceType").value("MANUAL_ADJUSTMENT"))
                .andExpect(jsonPath("$.referenceId").value(referenceId.toString()))
                .andExpect(jsonPath("$.performedByUserId").value(fixture.userId().toString()));

        assertThat(balanceQuantity(fixture)).isEqualByComparingTo("15.000");
        assertThat(balanceReservedQuantity(fixture)).isEqualByComparingTo("2.000");
        assertThat(countMovements(fixture)).isOne();
    }

    @Test
    void unauthenticatedAdjustmentIsRejected() throws Exception {
        mockMvc.perform(post(ADJUSTMENTS)
                        .contentType(APPLICATION_JSON)
                        .content(requestJson(
                                UUID.randomUUID(),
                                UUID.randomUUID(),
                                "in",
                                "1.000",
                                "Conteo físico",
                                null)))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void adjustmentWithoutPermissionIsRejected() throws Exception {
        Fixture fixture = createFixture();
        given(permissionResolver.hasPermission(
                        any(UUID.class), any(UUID.class), eq(CREATE_PERMISSION)))
                .willReturn(false);

        mockMvc.perform(post(ADJUSTMENTS)
                        .header("Authorization", token(fixture))
                        .contentType(APPLICATION_JSON)
                        .content(validRequest(fixture, "in", "1.000")))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("ACCESS_DENIED"));

        assertThat(countBalances(fixture)).isZero();
        assertThat(countMovements(fixture)).isZero();
    }

    @Test
    void tenantWithoutInventoryCapabilityIsRejected() throws Exception {
        Fixture fixture = createFixture();
        given(entitlementResolver.resolve(fixture.tenantId()))
                .willReturn(new TenantEntitlements(
                        true, true, EnumSet.of(SaasCapability.pos)));

        mockMvc.perform(post(ADJUSTMENTS)
                        .header("Authorization", token(fixture))
                        .contentType(APPLICATION_JSON)
                        .content(validRequest(fixture, "in", "1.000")))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("CAPABILITY_REQUIRED"));

        assertThat(countBalances(fixture)).isZero();
        assertThat(countMovements(fixture)).isZero();
    }

    @Test
    void crossTenantBranchCannotBeAdjusted() throws Exception {
        Fixture tenantA = createFixture();
        Fixture tenantB = createFixture();
        insertBalance(tenantB, "7.000", "1.000");

        mockMvc.perform(post(ADJUSTMENTS)
                        .header("Authorization", token(tenantA))
                        .contentType(APPLICATION_JSON)
                        .content(requestJson(
                                tenantB.branchId(),
                                tenantA.productId(),
                                "in",
                                "1.000",
                                "Ajuste cruzado",
                                null)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("BRANCH_NOT_FOUND"));

        assertThat(balanceQuantity(tenantB)).isEqualByComparingTo("7.000");
        assertThat(countMovements(tenantB)).isZero();
        assertThat(countBalances(
                        tenantA.tenantId(), tenantB.branchId(), tenantA.productId()))
                .isZero();
        assertThat(countMovements(
                        tenantA.tenantId(), tenantB.branchId(), tenantA.productId()))
                .isZero();
    }

    @Test
    void crossTenantProductCannotBeAdjusted() throws Exception {
        Fixture tenantA = createFixture();
        Fixture tenantB = createFixture();
        insertBalance(tenantB, "7.000", "1.000");

        mockMvc.perform(post(ADJUSTMENTS)
                        .header("Authorization", token(tenantA))
                        .contentType(APPLICATION_JSON)
                        .content(requestJson(
                                tenantA.branchId(),
                                tenantB.productId(),
                                "in",
                                "1.000",
                                "Ajuste cruzado",
                                null)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("PRODUCT_NOT_FOUND"));

        assertThat(balanceQuantity(tenantB)).isEqualByComparingTo("7.000");
        assertThat(countMovements(tenantB)).isZero();
        assertThat(countBalances(
                        tenantA.tenantId(), tenantA.branchId(), tenantB.productId()))
                .isZero();
        assertThat(countMovements(
                        tenantA.tenantId(), tenantA.branchId(), tenantB.productId()))
                .isZero();
    }

    @Test
    void unsupportedTransferTypeReturns400() throws Exception {
        Fixture fixture = createFixture();

        mockMvc.perform(post(ADJUSTMENTS)
                        .header("Authorization", token(fixture))
                        .contentType(APPLICATION_JSON)
                        .content(validRequest(fixture, "transfer", "1.000")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("REQUEST_ERROR"));
    }

    @Test
    void malformedRequestReturns400() throws Exception {
        Fixture fixture = createFixture();

        mockMvc.perform(post(ADJUSTMENTS)
                        .header("Authorization", token(fixture))
                        .contentType(APPLICATION_JSON)
                        .content("{"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("REQUEST_ERROR"));
    }

    @Test
    void nonPositiveQuantityReturns400() throws Exception {
        Fixture fixture = createFixture();

        mockMvc.perform(post(ADJUSTMENTS)
                        .header("Authorization", token(fixture))
                        .contentType(APPLICATION_JSON)
                        .content(validRequest(fixture, "in", "0")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));

        assertThat(countBalances(fixture)).isZero();
        assertThat(countMovements(fixture)).isZero();
    }

    @Test
    void outWithoutAvailableStockReturns409() throws Exception {
        Fixture fixture = createFixture();
        insertBalance(fixture, "10.000", "8.000");

        mockMvc.perform(post(ADJUSTMENTS)
                        .header("Authorization", token(fixture))
                        .contentType(APPLICATION_JSON)
                        .content(validRequest(fixture, "out", "3.000")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("INSUFFICIENT_STOCK"));

        assertThat(balanceQuantity(fixture)).isEqualByComparingTo("10.000");
        assertThat(balanceReservedQuantity(fixture)).isEqualByComparingTo("8.000");
        assertThat(countMovements(fixture)).isZero();
    }

    private Fixture createFixture() {
        UUID tenantId = UUID.randomUUID();
        UUID branchId = UUID.randomUUID();
        UUID categoryId = UUID.randomUUID();
        UUID unitId = UUID.randomUUID();
        UUID productId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        String suffix = UUID.randomUUID().toString();

        jdbcTemplate.update(
                """
                INSERT INTO tenants (id, name, slug, status, default_currency, timezone)
                VALUES (?, ?, ?, 'active', 'GTQ', 'America/Guatemala')
                """,
                tenantId,
                "Tenant " + suffix,
                "tenant-" + suffix);
        jdbcTemplate.update(
                """
                INSERT INTO branches (id, tenant_id, code, name, type, status)
                VALUES (?, ?, ?, ?, 'main', 'active')
                """,
                branchId,
                tenantId,
                "MAIN-" + suffix,
                "Principal " + suffix);
        jdbcTemplate.update(
                """
                INSERT INTO categories (id, tenant_id, name, slug, status)
                VALUES (?, ?, ?, ?, 'active')
                """,
                categoryId,
                tenantId,
                "Categoria " + suffix,
                "categoria-" + suffix);
        jdbcTemplate.update(
                """
                INSERT INTO units (id, tenant_id, code, name, symbol, category, allows_decimals, status)
                VALUES (?, ?, ?, 'Unidad', 'und', 'unit', true, 'active')
                """,
                unitId,
                tenantId,
                "U-" + suffix.substring(0, 8));
        jdbcTemplate.update(
                """
                INSERT INTO products (id, tenant_id, sku, name, category_id, base_unit_id)
                VALUES (?, ?, ?, ?, ?, ?)
                """,
                productId,
                tenantId,
                "SKU-" + suffix,
                "Producto " + suffix,
                categoryId,
                unitId);
        jdbcTemplate.update(
                """
                INSERT INTO users (id, tenant_id, name, email, type, status, branch_id)
                VALUES (?, ?, ?, ?, 'employee', 'active', ?)
                """,
                userId,
                tenantId,
                "Usuario " + suffix,
                "usuario-" + suffix + "@example.com",
                branchId);

        return new Fixture(tenantId, branchId, productId, userId);
    }

    private void insertBalance(Fixture fixture, String quantity, String reservedQuantity) {
        jdbcTemplate.update(
                """
                INSERT INTO inventory_balances
                    (id, tenant_id, branch_id, product_id, location_id, quantity, reserved_quantity)
                VALUES (?, ?, ?, ?, NULL, ?::numeric, ?::numeric)
                """,
                UUID.randomUUID(),
                fixture.tenantId(),
                fixture.branchId(),
                fixture.productId(),
                quantity,
                reservedQuantity);
    }

    private BigDecimal balanceQuantity(Fixture fixture) {
        return jdbcTemplate.queryForObject(
                """
                SELECT quantity
                FROM inventory_balances
                WHERE tenant_id = ? AND branch_id = ? AND product_id = ? AND location_id IS NULL
                """,
                BigDecimal.class,
                fixture.tenantId(),
                fixture.branchId(),
                fixture.productId());
    }

    private BigDecimal balanceReservedQuantity(Fixture fixture) {
        return jdbcTemplate.queryForObject(
                """
                SELECT reserved_quantity
                FROM inventory_balances
                WHERE tenant_id = ? AND branch_id = ? AND product_id = ? AND location_id IS NULL
                """,
                BigDecimal.class,
                fixture.tenantId(),
                fixture.branchId(),
                fixture.productId());
    }

    private long countBalances(Fixture fixture) {
        return countBalances(fixture.tenantId(), fixture.branchId(), fixture.productId());
    }

    private long countBalances(UUID tenantId, UUID branchId, UUID productId) {
        Long count = jdbcTemplate.queryForObject(
                """
                SELECT count(*)
                FROM inventory_balances
                WHERE tenant_id = ? AND branch_id = ? AND product_id = ? AND location_id IS NULL
                """,
                Long.class,
                tenantId,
                branchId,
                productId);
        return count == null ? 0 : count;
    }

    private long countMovements(Fixture fixture) {
        return countMovements(fixture.tenantId(), fixture.branchId(), fixture.productId());
    }

    private long countMovements(UUID tenantId, UUID branchId, UUID productId) {
        Long count = jdbcTemplate.queryForObject(
                """
                SELECT count(*)
                FROM inventory_movements
                WHERE tenant_id = ? AND branch_id = ? AND product_id = ?
                """,
                Long.class,
                tenantId,
                branchId,
                productId);
        return count == null ? 0 : count;
    }

    private String validRequest(Fixture fixture, String type, String quantity) {
        return requestJson(
                fixture.branchId(),
                fixture.productId(),
                type,
                quantity,
                "Conteo físico",
                null);
    }

    private String requestJson(
            UUID branchId,
            UUID productId,
            String type,
            String quantity,
            String reason,
            UUID referenceId) {
        String referenceIdJson = referenceId == null ? "null" : "\"" + referenceId + "\"";
        return """
                {
                  "branchId": "%s",
                  "productId": "%s",
                  "type": "%s",
                  "quantity": %s,
                  "reason": "%s",
                  "referenceType": "MANUAL_ADJUSTMENT",
                  "referenceId": %s
                }
                """
                .formatted(branchId, productId, type, quantity, reason, referenceIdJson);
    }

    private String token(Fixture fixture) {
        User user = User.builder()
                .name("Usuario inventario")
                .email("inventario-" + UUID.randomUUID() + "@test.local")
                .type(UserType.employee)
                .roleId(UUID.randomUUID())
                .branchId(fixture.branchId())
                .build();
        ReflectionTestUtils.setField(user, "id", fixture.userId());
        user.setTenantId(fixture.tenantId());
        Session session = Session.builder()
                .id(UUID.randomUUID())
                .userId(user.getId())
                .expiresAt(Instant.now().plus(1, ChronoUnit.HOURS))
                .build();
        return "Bearer " + jwtService.generateToken(user, session);
    }

    private record Fixture(UUID tenantId, UUID branchId, UUID productId, UUID userId) {}
}
