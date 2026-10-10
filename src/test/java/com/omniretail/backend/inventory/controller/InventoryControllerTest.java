package com.omniretail.backend.inventory.controller;

import static org.assertj.core.api.Assertions.assertThat;
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
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(TestcontainersConfiguration.class)
@Transactional
class InventoryControllerTest {

    private static final String BALANCES = "/api/v1/inventory/balances";
    private static final String STOCK = "/api/v1/inventory/stock";
    private static final String CROSS_BRANCH_STOCK = "/api/v1/inventory/stock/branches";
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
    void stockEndpointRequiresPermissionAndReturnsCombinedSummary() throws Exception {
        Fixture fixture = createFixture();

        mockMvc.perform(get(STOCK)
                        .header("Authorization", token(fixture.tenantId()))
                        .param("branchId", fixture.firstBranchId().toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].productId").value(fixture.productId().toString()))
                .andExpect(jsonPath("$.items[0].status").value("out_of_stock"))
                .andExpect(jsonPath("$.summary.activeProducts").value(1))
                .andExpect(jsonPath("$.summary.lowStock").value(0))
                .andExpect(jsonPath("$.summary.outOfStock").value(1));

        given(permissionResolver.hasPermission(
                        any(UUID.class), any(UUID.class), eq(READ_PERMISSION)))
                .willReturn(false);
        mockMvc.perform(get(STOCK)
                        .header("Authorization", token(fixture.tenantId()))
                        .param("branchId", fixture.firstBranchId().toString()))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("ACCESS_DENIED"));
    }

    @Test
    void crossBranchStockReturnsAccessibleBranchesWithAvailableAndZeroInStableOrder()
            throws Exception {
        Fixture fixture = createFixture();
        UUID zeroStockBranchId = UUID.randomUUID();
        insertBranch(
                zeroStockBranchId,
                fixture.tenantId(),
                "ZERO-" + zeroStockBranchId,
                "Almacen sin stock",
                "warehouse");
        insertBalance(
                fixture.tenantId(), fixture.secondBranchId(), fixture.productId(), null,
                "20.000", "5.000");

        mockMvc.perform(get(CROSS_BRANCH_STOCK)
                        .header("Authorization", token(fixture.tenantId()))
                        .param("productId", fixture.productId().toString())
                        .param("branchId", fixture.firstBranchId().toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].branchId").value(zeroStockBranchId.toString()))
                .andExpect(jsonPath("$[0].branchName").value("Almacen sin stock"))
                .andExpect(jsonPath("$[0].availableQuantity").value(0.000))
                .andExpect(jsonPath("$[1].branchId").value(fixture.secondBranchId().toString()))
                .andExpect(jsonPath("$[1].availableQuantity").value(15.000))
                .andExpect(jsonPath("$[?(@.branchId == '%s')]"
                                .formatted(fixture.firstBranchId()))
                        .isEmpty());
    }

    @Test
    void crossBranchStockDoesNotExposeUnauthorizedBranches() throws Exception {
        Fixture fixture = createFixture();
        UUID unauthorizedBranchId = UUID.randomUUID();
        insertBranch(
                unauthorizedBranchId,
                fixture.tenantId(),
                "DENIED-" + unauthorizedBranchId,
                "Sucursal restringida",
                "warehouse");
        insertBalance(
                fixture.tenantId(), unauthorizedBranchId, fixture.productId(), null,
                "30.000", "0.000");
        given(branchAccessResolver.resolve(any())).willReturn(new BranchAccess(
                false, Set.of(fixture.firstBranchId(), fixture.secondBranchId())));

        mockMvc.perform(get(CROSS_BRANCH_STOCK)
                        .header("Authorization", token(fixture.tenantId()))
                        .param("productId", fixture.productId().toString())
                        .param("branchId", fixture.firstBranchId().toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].branchId").value(fixture.secondBranchId().toString()))
                .andExpect(jsonPath("$[?(@.branchId == '%s')]"
                                .formatted(unauthorizedBranchId))
                        .isEmpty());
    }

    @Test
    void crossBranchStockDoesNotExposeBranchesFromAnotherTenant() throws Exception {
        Fixture tenantA = createFixture();
        Fixture tenantB = createFixture();

        mockMvc.perform(get(CROSS_BRANCH_STOCK)
                        .header("Authorization", token(tenantA.tenantId()))
                        .param("productId", tenantA.productId().toString())
                        .param("branchId", tenantA.firstBranchId().toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].branchId").value(tenantA.secondBranchId().toString()))
                .andExpect(jsonPath("$[?(@.branchId == '%s')]"
                                .formatted(tenantB.firstBranchId()))
                        .isEmpty())
                .andExpect(jsonPath("$[?(@.branchId == '%s')]"
                                .formatted(tenantB.secondBranchId()))
                        .isEmpty());
    }

    @Test
    void crossBranchStockRejectsUnauthorizedCurrentBranch() throws Exception {
        Fixture fixture = createFixture();
        given(branchAccessResolver.resolve(any())).willReturn(
                new BranchAccess(false, Set.of(fixture.secondBranchId())));

        mockMvc.perform(get(CROSS_BRANCH_STOCK)
                        .header("Authorization", token(fixture.tenantId()))
                        .param("productId", fixture.productId().toString())
                        .param("branchId", fixture.firstBranchId().toString()))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("BRANCH_ACCESS_DENIED"));
    }

    @Test
    void crossBranchStockDoesNotAcceptBranchFromAnotherTenant() throws Exception {
        Fixture tenantA = createFixture();
        Fixture tenantB = createFixture();

        mockMvc.perform(get(CROSS_BRANCH_STOCK)
                        .header("Authorization", token(tenantA.tenantId()))
                        .param("productId", tenantA.productId().toString())
                        .param("branchId", tenantB.firstBranchId().toString()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("BRANCH_NOT_FOUND"));
    }

    @Test
    void crossBranchStockDoesNotAcceptProductFromAnotherTenant() throws Exception {
        Fixture tenantA = createFixture();
        Fixture tenantB = createFixture();

        mockMvc.perform(get(CROSS_BRANCH_STOCK)
                        .header("Authorization", token(tenantA.tenantId()))
                        .param("productId", tenantB.productId().toString())
                        .param("branchId", tenantA.firstBranchId().toString()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("PRODUCT_NOT_FOUND"));
    }

    @Test
    void crossBranchStockRequiresInventoryStockReadPermission() throws Exception {
        Fixture fixture = createFixture();
        given(permissionResolver.hasPermission(
                        any(UUID.class), any(UUID.class), eq(READ_PERMISSION)))
                .willReturn(false);

        mockMvc.perform(get(CROSS_BRANCH_STOCK)
                        .header("Authorization", token(fixture.tenantId()))
                        .param("productId", fixture.productId().toString())
                        .param("branchId", fixture.firstBranchId().toString()))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("ACCESS_DENIED"));
    }

    @Test
    void crossBranchStockRequiresInventoryCapability() throws Exception {
        Fixture fixture = createFixture();
        given(entitlementResolver.resolve(fixture.tenantId()))
                .willReturn(new TenantEntitlements(
                        true, true, EnumSet.of(SaasCapability.pos)));

        mockMvc.perform(get(CROSS_BRANCH_STOCK)
                        .header("Authorization", token(fixture.tenantId()))
                        .param("productId", fixture.productId().toString())
                        .param("branchId", fixture.firstBranchId().toString()))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("CAPABILITY_REQUIRED"));
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
    void sameTenantUnauthorizedBranchIsRejected() throws Exception {
        Fixture fixture = createFixture();
        given(branchAccessResolver.resolve(any()))
                .willReturn(new BranchAccess(false, Set.of(fixture.firstBranchId())));

        mockMvc.perform(get(BALANCES)
                        .header("Authorization", token(fixture.tenantId()))
                        .param("branchId", fixture.secondBranchId().toString()))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("BRANCH_ACCESS_DENIED"));
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

    @Test
    void balancesCanBeFilteredByTenantScopedProductAndKeepNullLocations() throws Exception {
        Fixture fixture = createFixture();
        UUID otherProduct = insertSiblingProduct(fixture);
        UUID expected = insertBalance(
                fixture.tenantId(), fixture.firstBranchId(), fixture.productId(), null,
                "4.000", "1.000");
        insertBalance(
                fixture.tenantId(), fixture.firstBranchId(), otherProduct, null,
                "8.000", "0.000");

        mockMvc.perform(get(BALANCES)
                        .header("Authorization", token(fixture.tenantId()))
                        .param("branchId", fixture.firstBranchId().toString())
                        .param("productId", fixture.productId().toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.items[0].id").value(expected.toString()))
                .andExpect(jsonPath("$.items[0].locationId").doesNotExist());
    }

    @Test
    void balanceProductFilterDoesNotAcceptAnotherTenantsProduct() throws Exception {
        Fixture tenantA = createFixture();
        Fixture tenantB = createFixture();

        mockMvc.perform(get(BALANCES)
                        .header("Authorization", token(tenantA.tenantId()))
                        .param("branchId", tenantA.firstBranchId().toString())
                        .param("productId", tenantB.productId().toString()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("PRODUCT_NOT_FOUND"));
    }

    @Test
    void balancesUseStablePagingAndRejectUnsupportedSortFields() throws Exception {
        Fixture fixture = createFixture();
        UUID firstLocation = insertLocation(fixture);
        UUID secondLocation = insertLocation(fixture);
        insertBalance(
                fixture.tenantId(), fixture.firstBranchId(), fixture.productId(), null,
                "1.000", "0.000");
        insertBalance(
                fixture.tenantId(), fixture.firstBranchId(), fixture.productId(), firstLocation,
                "2.000", "0.000");
        insertBalance(
                fixture.tenantId(), fixture.firstBranchId(), fixture.productId(), secondLocation,
                "3.000", "0.000");

        mockMvc.perform(get(BALANCES)
                        .header("Authorization", token(fixture.tenantId()))
                        .param("branchId", fixture.firstBranchId().toString())
                        .param("productId", fixture.productId().toString())
                        .param("size", "2")
                        .param("sort", "quantity,asc"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(2))
                .andExpect(jsonPath("$.items[0].quantity").value(1.000))
                .andExpect(jsonPath("$.items[1].quantity").value(2.000))
                .andExpect(jsonPath("$.totalItems").value(3));

        mockMvc.perform(get(BALANCES)
                        .header("Authorization", token(fixture.tenantId()))
                        .param("branchId", fixture.firstBranchId().toString())
                        .param("productId", fixture.productId().toString())
                        .param("page", "2")
                        .param("size", "2")
                        .param("sort", "quantity,asc"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.items[0].quantity").value(3.000));

        mockMvc.perform(get(BALANCES)
                        .header("Authorization", token(fixture.tenantId()))
                        .param("branchId", fixture.firstBranchId().toString())
                        .param("sort", "tenantId,asc"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVENTORY_BALANCE_SORT_INVALID"));
    }

    @Test
    void balancesPreserveLegacySortFieldsAndPageSizeContract() throws Exception {
        Fixture fixture = createFixture();
        insertBalance(
                fixture.tenantId(), fixture.firstBranchId(), fixture.productId(), null,
                "1.000", "0.000");

        mockMvc.perform(get(BALANCES)
                        .header("Authorization", token(fixture.tenantId()))
                        .param("branchId", fixture.firstBranchId().toString())
                        .param("size", "150")
                        .param("sort", "createdAt,asc"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.pageSize").value(150));

        mockMvc.perform(get(BALANCES)
                        .header("Authorization", token(fixture.tenantId()))
                        .param("branchId", fixture.firstBranchId().toString())
                        .param("size", "2000")
                        .param("sort", "updatedAt,desc"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.pageSize").value(2000));

        mockMvc.perform(get(BALANCES)
                        .header("Authorization", token(fixture.tenantId()))
                        .param("branchId", fixture.firstBranchId().toString())
                        .param("size", "2001"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVENTORY_BALANCE_PAGE_SIZE_INVALID"));
    }

    @Test
    void balancePagingUsesIdAsUniqueTieBreakerAndHandlesNullLocation() throws Exception {
        Fixture fixture = createFixture();
        UUID firstLocation = insertLocation(fixture);
        UUID secondLocation = insertLocation(fixture);
        UUID first = insertBalance(
                fixture.tenantId(), fixture.firstBranchId(), fixture.productId(), firstLocation,
                "5.000", "0.000");
        UUID second = insertBalance(
                fixture.tenantId(), fixture.firstBranchId(), fixture.productId(), secondLocation,
                "5.000", "0.000");
        UUID legacy = insertBalance(
                fixture.tenantId(), fixture.firstBranchId(), fixture.productId(), null,
                "5.000", "0.000");

        String firstPage = mockMvc.perform(get(BALANCES)
                        .header("Authorization", token(fixture.tenantId()))
                        .param("branchId", fixture.firstBranchId().toString())
                        .param("productId", fixture.productId().toString())
                        .param("size", "1")
                        .param("sort", "quantity,asc"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        String repeatedFirstPage = mockMvc.perform(get(BALANCES)
                        .header("Authorization", token(fixture.tenantId()))
                        .param("branchId", fixture.firstBranchId().toString())
                        .param("productId", fixture.productId().toString())
                        .param("size", "1")
                        .param("sort", "quantity,asc"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertThat(firstPage).isEqualTo(repeatedFirstPage);
        mockMvc.perform(get(BALANCES)
                        .header("Authorization", token(fixture.tenantId()))
                        .param("branchId", fixture.firstBranchId().toString())
                        .param("productId", fixture.productId().toString())
                        .param("size", "3")
                        .param("sort", "quantity,asc"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(3))
                .andExpect(jsonPath("$.items[?(@.id == '%s')].locationId", first).exists())
                .andExpect(jsonPath("$.items[?(@.id == '%s')].locationId", second).exists())
                .andExpect(jsonPath("$.items[?(@.id == '%s')].locationId", legacy)
                        .value(org.hamcrest.Matchers.contains(org.hamcrest.Matchers.nullValue())));
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

    private UUID insertSiblingProduct(Fixture fixture) {
        UUID id = UUID.randomUUID();
        jdbcTemplate.update("""
                INSERT INTO products (id, tenant_id, sku, name, category_id, base_unit_id)
                SELECT ?, tenant_id, ?, 'Producto adicional', category_id, base_unit_id
                FROM products WHERE id = ?
                """, id, "SKU-" + id, fixture.productId());
        return id;
    }

    private UUID insertLocation(Fixture fixture) {
        UUID id = UUID.randomUUID();
        jdbcTemplate.update("""
                INSERT INTO locations (id, tenant_id, branch_id, code, name, type, status)
                VALUES (?, ?, ?, ?, 'Ubicacion', 'warehouse', 'active')
                """, id, fixture.tenantId(), fixture.firstBranchId(), "L-" + id);
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
