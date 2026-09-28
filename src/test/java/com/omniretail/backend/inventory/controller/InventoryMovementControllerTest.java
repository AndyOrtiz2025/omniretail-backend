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
import java.sql.Timestamp;
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
class InventoryMovementControllerTest {

    private static final String MOVEMENTS = "/api/v1/inventory/movements";
    private static final String READ_PERMISSION = "inventory.movements.read";
    private static final Instant BASE_TIME = Instant.parse("2026-01-15T12:00:00Z");

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
    void unauthenticatedRequestIsRejected() throws Exception {
        mockMvc.perform(get(MOVEMENTS)).andExpect(status().isUnauthorized());
    }

    @Test
    void requestWithoutPermissionIsRejected() throws Exception {
        Fixture fixture = createFixture();
        given(permissionResolver.hasPermission(
                        any(UUID.class), any(UUID.class), eq(READ_PERMISSION)))
                .willReturn(false);

        mockMvc.perform(get(MOVEMENTS).header("Authorization", token(fixture)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("ACCESS_DENIED"));
    }

    @Test
    void tenantWithoutInventoryCapabilityIsRejected() throws Exception {
        Fixture fixture = createFixture();
        given(entitlementResolver.resolve(fixture.tenantId()))
                .willReturn(new TenantEntitlements(
                        true, true, EnumSet.of(SaasCapability.pos)));

        mockMvc.perform(get(MOVEMENTS).header("Authorization", token(fixture)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("CAPABILITY_REQUIRED"));
    }

    @Test
    void emptyMovementListReturnsPageMetadata() throws Exception {
        Fixture fixture = createFixture();

        mockMvc.perform(get(MOVEMENTS)
                        .header("Authorization", token(fixture))
                        .param("page", "1")
                        .param("size", "20"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(0))
                .andExpect(jsonPath("$.page").value(1))
                .andExpect(jsonPath("$.pageSize").value(20))
                .andExpect(jsonPath("$.totalItems").value(0))
                .andExpect(jsonPath("$.totalPages").value(0));
    }

    @Test
    void authorizedTenantCanListMovements() throws Exception {
        Fixture fixture = createFixture();
        UUID movementId = insertMovement(
                fixture,
                fixture.firstBranchId(),
                fixture.firstProductId(),
                "in",
                BASE_TIME,
                null,
                null);

        mockMvc.perform(get(MOVEMENTS).header("Authorization", token(fixture)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].id").value(movementId.toString()))
                .andExpect(jsonPath("$.items[0].tenantId")
                        .value(fixture.tenantId().toString()))
                .andExpect(jsonPath("$.items[0].branchId")
                        .value(fixture.firstBranchId().toString()))
                .andExpect(jsonPath("$.items[0].productId")
                        .value(fixture.firstProductId().toString()))
                .andExpect(jsonPath("$.items[0].type").value("in"))
                .andExpect(jsonPath("$.items[0].reason").value("Movimiento de prueba"))
                .andExpect(jsonPath("$.items[0].quantity").value(2.000))
                .andExpect(jsonPath("$.items[0].quantityBefore").value(3.000))
                .andExpect(jsonPath("$.items[0].quantityAfter").value(5.000))
                .andExpect(jsonPath("$.items[0].referenceType").value("TEST"))
                .andExpect(jsonPath("$.items[0].createdAt").value(BASE_TIME.toString()))
                .andExpect(jsonPath("$.totalItems").value(1));
    }

    @Test
    void movementsAreIsolatedByTenant() throws Exception {
        Fixture tenantA = createFixture();
        Fixture tenantB = createFixture();
        UUID tenantAMovement = insertMovement(
                tenantA,
                tenantA.firstBranchId(),
                tenantA.firstProductId(),
                "in",
                BASE_TIME,
                null,
                null);
        insertMovement(
                tenantB,
                tenantB.firstBranchId(),
                tenantB.firstProductId(),
                "out",
                BASE_TIME.plusSeconds(1),
                null,
                null);

        mockMvc.perform(get(MOVEMENTS).header("Authorization", token(tenantA)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.items[0].id").value(tenantAMovement.toString()))
                .andExpect(jsonPath("$.totalItems").value(1));
    }

    @Test
    void validBranchFilterReturnsOnlyThatBranch() throws Exception {
        Fixture fixture = createFixture();
        UUID expected = insertMovement(
                fixture,
                fixture.firstBranchId(),
                fixture.firstProductId(),
                "in",
                BASE_TIME,
                null,
                null);
        insertMovement(
                fixture,
                fixture.secondBranchId(),
                fixture.firstProductId(),
                "out",
                BASE_TIME,
                null,
                null);

        mockMvc.perform(get(MOVEMENTS)
                        .header("Authorization", token(fixture))
                        .param("branchId", fixture.firstBranchId().toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.items[0].id").value(expected.toString()))
                .andExpect(jsonPath("$.totalItems").value(1));
    }

    @Test
    void branchFromAnotherTenantReturnsNotFound() throws Exception {
        Fixture tenantA = createFixture();
        Fixture tenantB = createFixture();

        mockMvc.perform(get(MOVEMENTS)
                        .header("Authorization", token(tenantA))
                        .param("branchId", tenantB.firstBranchId().toString()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("BRANCH_NOT_FOUND"))
                .andExpect(jsonPath("$.message").value("Sucursal no encontrada."));
    }

    @Test
    void validProductFilterReturnsOnlyThatProduct() throws Exception {
        Fixture fixture = createFixture();
        UUID expected = insertMovement(
                fixture,
                fixture.firstBranchId(),
                fixture.firstProductId(),
                "in",
                BASE_TIME,
                null,
                null);
        insertMovement(
                fixture,
                fixture.firstBranchId(),
                fixture.secondProductId(),
                "out",
                BASE_TIME,
                null,
                null);

        mockMvc.perform(get(MOVEMENTS)
                        .header("Authorization", token(fixture))
                        .param("productId", fixture.firstProductId().toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.items[0].id").value(expected.toString()))
                .andExpect(jsonPath("$.totalItems").value(1));
    }

    @Test
    void productFromAnotherTenantReturnsNotFound() throws Exception {
        Fixture tenantA = createFixture();
        Fixture tenantB = createFixture();

        mockMvc.perform(get(MOVEMENTS)
                        .header("Authorization", token(tenantA))
                        .param("productId", tenantB.firstProductId().toString()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("PRODUCT_NOT_FOUND"))
                .andExpect(jsonPath("$.message").value("Producto no encontrado."));
    }

    @Test
    void typeInFilterReturnsOnlyIncomingMovements() throws Exception {
        assertTypeFilter("in");
    }

    @Test
    void typeOutFilterReturnsOnlyOutgoingMovements() throws Exception {
        assertTypeFilter("out");
    }

    @Test
    void typeTransferFilterReturnsOnlyTransferMovements() throws Exception {
        assertTypeFilter("transfer");
    }

    @Test
    void invalidTypeReturnsBadRequest() throws Exception {
        Fixture fixture = createFixture();

        mockMvc.perform(get(MOVEMENTS)
                        .header("Authorization", token(fixture))
                        .param("type", "invalid"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("REQUEST_ERROR"));
    }

    @Test
    void fromFilterIsInclusive() throws Exception {
        Fixture fixture = createFixture();
        insertMovement(
                fixture,
                fixture.firstBranchId(),
                fixture.firstProductId(),
                "in",
                BASE_TIME.minusSeconds(1),
                null,
                null);
        UUID expected = insertMovement(
                fixture,
                fixture.firstBranchId(),
                fixture.firstProductId(),
                "out",
                BASE_TIME,
                null,
                null);

        mockMvc.perform(get(MOVEMENTS)
                        .header("Authorization", token(fixture))
                        .param("from", BASE_TIME.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.items[0].id").value(expected.toString()));
    }

    @Test
    void toFilterIsInclusive() throws Exception {
        Fixture fixture = createFixture();
        UUID expected = insertMovement(
                fixture,
                fixture.firstBranchId(),
                fixture.firstProductId(),
                "in",
                BASE_TIME,
                null,
                null);
        insertMovement(
                fixture,
                fixture.firstBranchId(),
                fixture.firstProductId(),
                "out",
                BASE_TIME.plusSeconds(1),
                null,
                null);

        mockMvc.perform(get(MOVEMENTS)
                        .header("Authorization", token(fixture))
                        .param("to", BASE_TIME.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.items[0].id").value(expected.toString()));
    }

    @Test
    void fromAndToFilterIsInclusive() throws Exception {
        Fixture fixture = createFixture();
        insertMovement(
                fixture,
                fixture.firstBranchId(),
                fixture.firstProductId(),
                "in",
                BASE_TIME.minusSeconds(1),
                null,
                null);
        UUID firstExpected = insertMovement(
                fixture,
                fixture.firstBranchId(),
                fixture.firstProductId(),
                "out",
                BASE_TIME,
                null,
                null);
        UUID secondExpected = insertMovement(
                fixture,
                fixture.firstBranchId(),
                fixture.firstProductId(),
                "transfer",
                BASE_TIME.plusSeconds(1),
                null,
                null);
        insertMovement(
                fixture,
                fixture.firstBranchId(),
                fixture.firstProductId(),
                "in",
                BASE_TIME.plusSeconds(2),
                null,
                null);

        mockMvc.perform(get(MOVEMENTS)
                        .header("Authorization", token(fixture))
                        .param("from", BASE_TIME.toString())
                        .param("to", BASE_TIME.plusSeconds(1).toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(2))
                .andExpect(jsonPath("$.items[0].id").value(secondExpected.toString()))
                .andExpect(jsonPath("$.items[1].id").value(firstExpected.toString()));
    }

    @Test
    void fromAfterToReturnsBadRequest() throws Exception {
        Fixture fixture = createFixture();

        mockMvc.perform(get(MOVEMENTS)
                        .header("Authorization", token(fixture))
                        .param("from", BASE_TIME.plusSeconds(1).toString())
                        .param("to", BASE_TIME.toString()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("BAD_REQUEST"))
                .andExpect(jsonPath("$.message")
                        .value("La fecha inicial no puede ser posterior a la fecha final."));
    }

    @Test
    void allFiltersCanBeCombined() throws Exception {
        Fixture fixture = createFixture();
        Instant from = BASE_TIME;
        Instant to = BASE_TIME.plusSeconds(10);
        UUID expected = insertMovement(
                fixture,
                fixture.firstBranchId(),
                fixture.firstProductId(),
                "transfer",
                BASE_TIME.plusSeconds(5),
                null,
                null);
        insertMovement(
                fixture,
                fixture.secondBranchId(),
                fixture.firstProductId(),
                "transfer",
                BASE_TIME.plusSeconds(5),
                null,
                null);
        insertMovement(
                fixture,
                fixture.firstBranchId(),
                fixture.secondProductId(),
                "transfer",
                BASE_TIME.plusSeconds(5),
                null,
                null);
        insertMovement(
                fixture,
                fixture.firstBranchId(),
                fixture.firstProductId(),
                "in",
                BASE_TIME.plusSeconds(5),
                null,
                null);
        insertMovement(
                fixture,
                fixture.firstBranchId(),
                fixture.firstProductId(),
                "transfer",
                BASE_TIME.plusSeconds(11),
                null,
                null);

        mockMvc.perform(get(MOVEMENTS)
                        .header("Authorization", token(fixture))
                        .param("branchId", fixture.firstBranchId().toString())
                        .param("productId", fixture.firstProductId().toString())
                        .param("type", "transfer")
                        .param("from", from.toString())
                        .param("to", to.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.items[0].id").value(expected.toString()))
                .andExpect(jsonPath("$.totalItems").value(1));
    }

    @Test
    void defaultOrderIsCreatedAtDescending() throws Exception {
        Fixture fixture = createFixture();
        UUID oldest = insertMovement(
                fixture,
                fixture.firstBranchId(),
                fixture.firstProductId(),
                "in",
                BASE_TIME,
                null,
                null);
        UUID newest = insertMovement(
                fixture,
                fixture.firstBranchId(),
                fixture.firstProductId(),
                "out",
                BASE_TIME.plusSeconds(10),
                null,
                null);

        mockMvc.perform(get(MOVEMENTS).header("Authorization", token(fixture)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].id").value(newest.toString()))
                .andExpect(jsonPath("$.items[1].id").value(oldest.toString()));
    }

    @Test
    void paginationReturnsRequestedPageAndCorrectMetadata() throws Exception {
        Fixture fixture = createFixture();
        insertMovement(
                fixture,
                fixture.firstBranchId(),
                fixture.firstProductId(),
                "in",
                BASE_TIME,
                null,
                null);
        UUID middle = insertMovement(
                fixture,
                fixture.firstBranchId(),
                fixture.firstProductId(),
                "out",
                BASE_TIME.plusSeconds(1),
                null,
                null);
        insertMovement(
                fixture,
                fixture.firstBranchId(),
                fixture.firstProductId(),
                "transfer",
                BASE_TIME.plusSeconds(2),
                null,
                null);

        mockMvc.perform(get(MOVEMENTS)
                        .header("Authorization", token(fixture))
                        .param("page", "2")
                        .param("size", "1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.items[0].id").value(middle.toString()))
                .andExpect(jsonPath("$.page").value(2))
                .andExpect(jsonPath("$.pageSize").value(1))
                .andExpect(jsonPath("$.totalItems").value(3))
                .andExpect(jsonPath("$.totalPages").value(3));
    }

    @Test
    void responseIncludesFromAndToLocationIds() throws Exception {
        Fixture fixture = createFixture();
        UUID fromLocationId = UUID.randomUUID();
        UUID toLocationId = UUID.randomUUID();
        jdbcTemplate.update(
                """
                INSERT INTO locations
                    (id, tenant_id, branch_id, parent_id, code, name, type, status)
                VALUES (?, ?, ?, NULL, 'MOV-FROM', 'Ubicación origen',
                        'warehouse', 'active')
                """,
                fromLocationId,
                fixture.tenantId(),
                fixture.firstBranchId());
        jdbcTemplate.update(
                """
                INSERT INTO locations
                    (id, tenant_id, branch_id, parent_id, code, name, type, status)
                VALUES (?, ?, ?, NULL, 'MOV-TO', 'Ubicación destino',
                        'warehouse', 'active')
                """,
                toLocationId,
                fixture.tenantId(),
                fixture.firstBranchId());
        insertMovement(
                fixture,
                fixture.firstBranchId(),
                fixture.firstProductId(),
                "transfer",
                BASE_TIME,
                fromLocationId,
                toLocationId);

        mockMvc.perform(get(MOVEMENTS).header("Authorization", token(fixture)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].fromLocationId")
                        .value(fromLocationId.toString()))
                .andExpect(jsonPath("$.items[0].toLocationId")
                        .value(toLocationId.toString()));
    }

    @Test
    void movementsFromOtherTenantsDoNotAffectTotalItems() throws Exception {
        Fixture tenantA = createFixture();
        Fixture tenantB = createFixture();
        insertMovement(
                tenantA,
                tenantA.firstBranchId(),
                tenantA.firstProductId(),
                "in",
                BASE_TIME,
                null,
                null);
        insertMovement(
                tenantB,
                tenantB.firstBranchId(),
                tenantB.firstProductId(),
                "in",
                BASE_TIME,
                null,
                null);
        insertMovement(
                tenantB,
                tenantB.firstBranchId(),
                tenantB.firstProductId(),
                "out",
                BASE_TIME.plusSeconds(1),
                null,
                null);

        mockMvc.perform(get(MOVEMENTS)
                        .header("Authorization", token(tenantA))
                        .param("size", "1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.totalItems").value(1))
                .andExpect(jsonPath("$.totalPages").value(1));
    }

    private void assertTypeFilter(String requestedType) throws Exception {
        Fixture fixture = createFixture();
        UUID expected = null;
        for (String type : new String[] {"in", "out", "transfer"}) {
            UUID movementId = insertMovement(
                    fixture,
                    fixture.firstBranchId(),
                    fixture.firstProductId(),
                    type,
                    BASE_TIME,
                    null,
                    null);
            if (type.equals(requestedType)) {
                expected = movementId;
            }
        }

        mockMvc.perform(get(MOVEMENTS)
                        .header("Authorization", token(fixture))
                        .param("type", requestedType))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.items[0].id").value(expected.toString()))
                .andExpect(jsonPath("$.items[0].type").value(requestedType));
    }

    private Fixture createFixture() {
        UUID tenantId = UUID.randomUUID();
        UUID firstBranchId = UUID.randomUUID();
        UUID secondBranchId = UUID.randomUUID();
        UUID categoryId = UUID.randomUUID();
        UUID unitId = UUID.randomUUID();
        UUID firstProductId = UUID.randomUUID();
        UUID secondProductId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        String suffix = UUID.randomUUID().toString();
        String shortSuffix = suffix.substring(0, 8);

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
                VALUES (?, ?, ?, ?, 'main', 'active'), (?, ?, ?, ?, 'store', 'active')
                """,
                firstBranchId,
                tenantId,
                "A-" + shortSuffix,
                "Sucursal A " + suffix,
                secondBranchId,
                tenantId,
                "B-" + shortSuffix,
                "Sucursal B " + suffix);
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
                "U-" + shortSuffix);
        jdbcTemplate.update(
                """
                INSERT INTO products (id, tenant_id, sku, name, category_id, base_unit_id)
                VALUES (?, ?, ?, ?, ?, ?), (?, ?, ?, ?, ?, ?)
                """,
                firstProductId,
                tenantId,
                "SKU-A-" + suffix,
                "Producto A " + suffix,
                categoryId,
                unitId,
                secondProductId,
                tenantId,
                "SKU-B-" + suffix,
                "Producto B " + suffix,
                categoryId,
                unitId);

        return new Fixture(
                tenantId,
                firstBranchId,
                secondBranchId,
                firstProductId,
                secondProductId,
                userId);
    }

    private UUID insertMovement(
            Fixture fixture,
            UUID branchId,
            UUID productId,
            String type,
            Instant createdAt,
            UUID fromLocationId,
            UUID toLocationId) {
        UUID movementId = UUID.randomUUID();
        jdbcTemplate.update(
                """
                INSERT INTO inventory_movements
                    (id, tenant_id, branch_id, product_id, type, reason, quantity,
                     quantity_before, quantity_after, from_location_id, to_location_id,
                     reference_type, reference_id, performed_by_user_id, created_at)
                VALUES (?, ?, ?, ?, ?, 'Movimiento de prueba', 2.000, 3.000, 5.000,
                        ?, ?, 'TEST', ?, NULL, ?)
                """,
                movementId,
                fixture.tenantId(),
                branchId,
                productId,
                type,
                fromLocationId,
                toLocationId,
                UUID.randomUUID(),
                Timestamp.from(createdAt));
        return movementId;
    }

    private String token(Fixture fixture) {
        User user = User.builder()
                .name("Usuario inventario")
                .email("inventario-" + UUID.randomUUID() + "@test.local")
                .type(UserType.employee)
                .roleId(UUID.randomUUID())
                .branchId(fixture.firstBranchId())
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

    private record Fixture(
            UUID tenantId,
            UUID firstBranchId,
            UUID secondBranchId,
            UUID firstProductId,
            UUID secondProductId,
            UUID userId) {}
}
