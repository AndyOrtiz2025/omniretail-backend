package com.omniretail.backend.inventory.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
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
import java.time.temporal.ChronoUnit;
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
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(TestcontainersConfiguration.class)
@Transactional
class InventoryControllerTest {

    private static final String BALANCES = "/api/v1/inventory/balances";
    private static final String READ_PERMISSION = "inventory.stock.read";

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

    @BeforeEach
    void setUp() {
        given(sessionService.isActive(any(), any())).willReturn(true);
        given(permissionResolver.hasPermission(
                        any(UUID.class), any(UUID.class), anyString()))
                .willReturn(true);
        given(entitlementResolver.resolve(any(UUID.class)))
                .willReturn(new TenantEntitlements(
                        true, true, EnumSet.allOf(SaasCapability.class)));
    }

    @Test
    void authorizedTenantCanListItsBranchBalances() throws Exception {
        Fixture fixture = createFixture();
        UUID balanceId = insertBalance(
                fixture.tenantId(), fixture.firstBranchId(), fixture.productId(), null,
                "10.000", "2.000");

        mockMvc.perform(get(BALANCES)
                        .header("Authorization", token(fixture.tenantId()))
                        .param("branchId", fixture.firstBranchId().toString())
                        .param("page", "1")
                        .param("size", "20"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].id").value(balanceId.toString()))
                .andExpect(jsonPath("$.items[0].tenantId")
                        .value(fixture.tenantId().toString()))
                .andExpect(jsonPath("$.items[0].branchId")
                        .value(fixture.firstBranchId().toString()))
                .andExpect(jsonPath("$.items[0].productId")
                        .value(fixture.productId().toString()))
                .andExpect(jsonPath("$.items[0].quantity").value(10.000))
                .andExpect(jsonPath("$.items[0].reservedQuantity").value(2.000))
                .andExpect(jsonPath("$.page").value(1))
                .andExpect(jsonPath("$.pageSize").value(20))
                .andExpect(jsonPath("$.totalItems").value(1))
                .andExpect(jsonPath("$.totalPages").value(1));
    }

    @Test
    void emptyBranchReturnsEmptyPage() throws Exception {
        Fixture fixture = createFixture();

        mockMvc.perform(get(BALANCES)
                        .header("Authorization", token(fixture.tenantId()))
                        .param("branchId", fixture.firstBranchId().toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items").isEmpty())
                .andExpect(jsonPath("$.page").value(1))
                .andExpect(jsonPath("$.totalItems").value(0))
                .andExpect(jsonPath("$.totalPages").value(0));
    }

    @Test
    void balancesFromAnotherBranchAreExcluded() throws Exception {
        Fixture fixture = createFixture();
        UUID expectedId = insertBalance(
                fixture.tenantId(), fixture.firstBranchId(), fixture.productId(), null,
                "5.000", "0.000");
        UUID excludedId = insertBalance(
                fixture.tenantId(), fixture.secondBranchId(), fixture.productId(), null,
                "9.000", "1.000");

        mockMvc.perform(get(BALANCES)
                        .header("Authorization", token(fixture.tenantId()))
                        .param("branchId", fixture.firstBranchId().toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.items[0].id").value(expectedId.toString()))
                .andExpect(jsonPath("$.items[?(@.id == '%s')]"
                                .formatted(excludedId))
                        .isEmpty());
    }

    @Test
    void crossTenantBranchUuidCannotExposeBalances() throws Exception {
        Fixture tenantA = createFixture();
        Fixture tenantB = createFixture();
        insertBalance(
                tenantB.tenantId(), tenantB.firstBranchId(), tenantB.productId(), null,
                "7.000", "0.000");

        mockMvc.perform(get(BALANCES)
                        .header("Authorization", token(tenantA.tenantId()))
                        .param("branchId", tenantB.firstBranchId().toString()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("BRANCH_NOT_FOUND"));
    }

    @Test
    void balancesFromAnotherTenantAreExcluded() throws Exception {
        Fixture tenantA = createFixture();
        Fixture tenantB = createFixture();
        UUID expectedId = insertBalance(
                tenantA.tenantId(), tenantA.firstBranchId(), tenantA.productId(), null,
                "4.000", "0.000");
        UUID excludedId = insertBalance(
                tenantB.tenantId(), tenantB.firstBranchId(), tenantB.productId(), null,
                "8.000", "0.000");

        mockMvc.perform(get(BALANCES)
                        .header("Authorization", token(tenantA.tenantId()))
                        .param("branchId", tenantA.firstBranchId().toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.items[0].id").value(expectedId.toString()))
                .andExpect(jsonPath("$.items[?(@.id == '%s')]"
                                .formatted(excludedId))
                        .isEmpty());
    }

    @Test
    void missingPermissionIsRejected() throws Exception {
        Fixture fixture = createFixture();
        given(permissionResolver.hasPermission(
                        any(UUID.class), any(UUID.class), eq(READ_PERMISSION)))
                .willReturn(false);

        mockMvc.perform(get(BALANCES)
                        .header("Authorization", token(fixture.tenantId()))
                        .param("branchId", fixture.firstBranchId().toString()))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("ACCESS_DENIED"));
    }

    @Test
    void missingBranchParameterReturns400() throws Exception {
        Fixture fixture = createFixture();

        mockMvc.perform(get(BALANCES)
                        .header("Authorization", token(fixture.tenantId())))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("REQUEST_ERROR"));
    }

    @Test
    void malformedBranchIdReturns400() throws Exception {
        Fixture fixture = createFixture();

        mockMvc.perform(get(BALANCES)
                        .header("Authorization", token(fixture.tenantId()))
                        .param("branchId", "not-a-uuid"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("REQUEST_ERROR"));
    }

    @Test
    void nonexistentBranchReturns404() throws Exception {
        Fixture fixture = createFixture();

        mockMvc.perform(get(BALANCES)
                        .header("Authorization", token(fixture.tenantId()))
                        .param("branchId", UUID.randomUUID().toString()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("BRANCH_NOT_FOUND"));
    }

    @Test
    void tenantWithoutInventoryCapabilityIsRejected() throws Exception {
        Fixture fixture = createFixture();
        given(entitlementResolver.resolve(fixture.tenantId()))
                .willReturn(new TenantEntitlements(
                        true, true, EnumSet.of(SaasCapability.pos)));

        mockMvc.perform(get(BALANCES)
                        .header("Authorization", token(fixture.tenantId()))
                        .param("branchId", fixture.firstBranchId().toString()))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("CAPABILITY_REQUIRED"));
    }

    private Fixture createFixture() {
        UUID tenantId = UUID.randomUUID();
        UUID firstBranchId = UUID.randomUUID();
        UUID secondBranchId = UUID.randomUUID();
        UUID categoryId = UUID.randomUUID();
        UUID unitId = UUID.randomUUID();
        UUID productId = UUID.randomUUID();
        String suffix = UUID.randomUUID().toString();

        jdbcTemplate.update(
                """
                INSERT INTO tenants (id, name, slug, status, default_currency, timezone)
                VALUES (?, ?, ?, 'active', 'GTQ', 'America/Guatemala')
                """,
                tenantId,
                "Tenant " + suffix,
                "tenant-" + suffix);
        insertBranch(firstBranchId, tenantId, "MAIN-" + suffix, "Principal " + suffix, "main");
        insertBranch(secondBranchId, tenantId, "WH-" + suffix, "Bodega " + suffix, "warehouse");
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
                VALUES (?, ?, ?, 'Unidad', 'und', 'unit', false, 'active')
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

        return new Fixture(tenantId, firstBranchId, secondBranchId, productId);
    }

    private void insertBranch(
            UUID branchId, UUID tenantId, String code, String name, String type) {
        jdbcTemplate.update(
                """
                INSERT INTO branches (id, tenant_id, code, name, type, status)
                VALUES (?, ?, ?, ?, ?, 'active')
                """,
                branchId,
                tenantId,
                code,
                name,
                type);
    }

    private UUID insertBalance(
            UUID tenantId,
            UUID branchId,
            UUID productId,
            UUID locationId,
            String quantity,
            String reservedQuantity) {
        UUID id = UUID.randomUUID();
        jdbcTemplate.update(
                """
                INSERT INTO inventory_balances
                    (id, tenant_id, branch_id, product_id, location_id, quantity, reserved_quantity)
                VALUES (?, ?, ?, ?, ?, ?::numeric, ?::numeric)
                """,
                id,
                tenantId,
                branchId,
                productId,
                locationId,
                quantity,
                reservedQuantity);
        return id;
    }

    private String token(UUID tenantId) {
        User user = User.builder()
                .name("Usuario inventario")
                .email("inventario-" + UUID.randomUUID() + "@test.local")
                .type(UserType.employee)
                .roleId(UUID.randomUUID())
                .build();
        ReflectionTestUtils.setField(user, "id", UUID.randomUUID());
        user.setTenantId(tenantId);
        Session session = Session.builder()
                .id(UUID.randomUUID())
                .userId(user.getId())
                .expiresAt(Instant.now().plus(1, ChronoUnit.HOURS))
                .build();
        return "Bearer " + jwtService.generateToken(user, session);
    }

    private record Fixture(
            UUID tenantId, UUID firstBranchId, UUID secondBranchId, UUID productId) {}
}
