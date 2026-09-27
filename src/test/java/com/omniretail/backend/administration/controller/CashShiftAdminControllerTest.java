package com.omniretail.backend.administration.controller;

import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.omniretail.backend.TestcontainersConfiguration;
import com.omniretail.backend.administration.entity.BranchScope;
import com.omniretail.backend.administration.entity.Role;
import com.omniretail.backend.administration.entity.Tenant;
import com.omniretail.backend.administration.entity.TenantStatus;
import com.omniretail.backend.administration.entity.User;
import com.omniretail.backend.administration.entity.UserType;
import com.omniretail.backend.administration.repository.RoleRepository;
import com.omniretail.backend.administration.repository.TenantRepository;
import com.omniretail.backend.administration.repository.UserRepository;
import com.omniretail.backend.auth.entity.Session;
import com.omniretail.backend.auth.repository.SessionRepository;
import com.omniretail.backend.auth.service.JwtService;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(TestcontainersConfiguration.class)
class CashShiftAdminControllerTest {

    private static final String BASE_URL = "/api/v1/administration/cash-shifts";
    private static final List<String> CASH_READ = List.of("admin.cash.read");

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JwtService jwtService;

    @Autowired
    private TenantRepository tenantRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private RoleRepository roleRepository;

    @Autowired
    private SessionRepository sessionRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void withoutTokenReturnsUnauthorized() throws Exception {
        mockMvc.perform(get(BASE_URL)).andExpect(status().isUnauthorized());
    }

    @Test
    void withoutPermissionReturnsForbidden() throws Exception {
        Tenant tenant = persistTenant();
        String token = tokenFor(tenant, List.of());

        mockMvc.perform(get(BASE_URL).header("Authorization", bearer(token)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("ACCESS_DENIED"));
    }

    @Test
    void listCashShiftsEmptyReturnsEmptyList() throws Exception {
        Tenant tenant = persistTenant();
        String token = tokenFor(tenant, CASH_READ);

        mockMvc.perform(get(BASE_URL).header("Authorization", bearer(token)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray())
                .andExpect(jsonPath("$.length()").value(0));
    }

    @Test
    void listCashShiftsReturnsSortedByOpenedAtDesc() throws Exception {
        Tenant tenant = persistTenant();
        String token = tokenFor(tenant, CASH_READ);
        Fixture fixture = createFixture(tenant.getId());
        Instant now = Instant.now();

        // Solo puede haber un turno abierto por sucursal y usuario: los anteriores quedan cerrados.
        insertClosedShift(tenant.getId(), fixture.branchA(), fixture.cashierA(), "CAJA-HACE-2H",
                now.minus(2, ChronoUnit.HOURS));
        insertOpenShift(tenant.getId(), fixture.branchA(), fixture.cashierA(), "CAJA-HACE-10M",
                now.minus(10, ChronoUnit.MINUTES));
        insertClosedShift(tenant.getId(), fixture.branchA(), fixture.cashierB(), "CAJA-HACE-1H",
                now.minus(1, ChronoUnit.HOURS));

        mockMvc.perform(get(BASE_URL).header("Authorization", bearer(token)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(3))
                .andExpect(jsonPath("$[0].registerCode").value("CAJA-HACE-10M"))
                .andExpect(jsonPath("$[1].registerCode").value("CAJA-HACE-1H"))
                .andExpect(jsonPath("$[2].registerCode").value("CAJA-HACE-2H"));
    }

    @Test
    void listCashShiftsFilterByStatus() throws Exception {
        Tenant tenant = persistTenant();
        String token = tokenFor(tenant, CASH_READ);
        Fixture fixture = createFixture(tenant.getId());
        Instant now = Instant.now();

        insertOpenShift(tenant.getId(), fixture.branchA(), fixture.cashierA(), "CAJA-ABIERTA",
                now.minus(5, ChronoUnit.MINUTES));
        insertClosedShift(tenant.getId(), fixture.branchA(), fixture.cashierA(), "CAJA-CERRADA",
                now.minus(3, ChronoUnit.HOURS));

        mockMvc.perform(get(BASE_URL).param("status", "open").header("Authorization", bearer(token)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].registerCode").value("CAJA-ABIERTA"))
                .andExpect(jsonPath("$[0].status").value("open"));

        mockMvc.perform(get(BASE_URL).param("status", "closed").header("Authorization", bearer(token)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].registerCode").value("CAJA-CERRADA"));
    }

    @Test
    void listCashShiftsFilterByBranchId() throws Exception {
        Tenant tenant = persistTenant();
        String token = tokenFor(tenant, CASH_READ);
        Fixture fixture = createFixture(tenant.getId());
        Instant now = Instant.now();

        insertOpenShift(tenant.getId(), fixture.branchA(), fixture.cashierA(), "CAJA-CENTRO",
                now.minus(20, ChronoUnit.MINUTES));
        insertOpenShift(tenant.getId(), fixture.branchB(), fixture.cashierB(), "CAJA-NORTE",
                now.minus(15, ChronoUnit.MINUTES));

        mockMvc.perform(get(BASE_URL)
                        .param("branchId", fixture.branchB().toString())
                        .header("Authorization", bearer(token)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].registerCode").value("CAJA-NORTE"))
                .andExpect(jsonPath("$[0].branchId").value(fixture.branchB().toString()));
    }

    @Test
    void listCashShiftsFilterByStatusAndBranchId() throws Exception {
        Tenant tenant = persistTenant();
        String token = tokenFor(tenant, CASH_READ);
        Fixture fixture = createFixture(tenant.getId());
        Instant now = Instant.now();

        insertOpenShift(tenant.getId(), fixture.branchA(), fixture.cashierA(), "CENTRO-ABIERTA",
                now.minus(10, ChronoUnit.MINUTES));
        insertClosedShift(tenant.getId(), fixture.branchA(), fixture.cashierA(), "CENTRO-CERRADA",
                now.minus(5, ChronoUnit.HOURS));
        insertClosedShift(tenant.getId(), fixture.branchB(), fixture.cashierB(), "NORTE-CERRADA",
                now.minus(4, ChronoUnit.HOURS));

        mockMvc.perform(get(BASE_URL)
                        .param("status", "closed")
                        .param("branchId", fixture.branchA().toString())
                        .header("Authorization", bearer(token)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].registerCode").value("CENTRO-CERRADA"));
    }

    @Test
    void getCashShiftByIdSuccess() throws Exception {
        Tenant tenant = persistTenant();
        String token = tokenFor(tenant, CASH_READ);
        Fixture fixture = createFixture(tenant.getId());
        Instant openedAt = Instant.now().minus(8, ChronoUnit.HOURS);

        UUID shiftId = insertShift(
                tenant.getId(),
                fixture.branchA(),
                fixture.cashierA(),
                "CAJA-01",
                "closed_with_difference",
                openedAt,
                new BigDecimal("500.00"),
                openedAt.plus(8, ChronoUnit.HOURS),
                new BigDecimal("1750.50"),
                new BigDecimal("1740.00"),
                new BigDecimal("-10.50"));

        mockMvc.perform(get(BASE_URL + "/" + shiftId).header("Authorization", bearer(token)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(shiftId.toString()))
                .andExpect(jsonPath("$.branchId").value(fixture.branchA().toString()))
                .andExpect(jsonPath("$.userId").value(fixture.cashierA().toString()))
                .andExpect(jsonPath("$.registerCode").value("CAJA-01"))
                .andExpect(jsonPath("$.status").value("closed_with_difference"))
                .andExpect(jsonPath("$.openedAt").value(notNullValue()))
                .andExpect(jsonPath("$.openingAmount").value(500.0))
                .andExpect(jsonPath("$.closedAt").value(notNullValue()))
                .andExpect(jsonPath("$.expectedAmount").value(1750.5))
                .andExpect(jsonPath("$.countedAmount").value(1740.0))
                .andExpect(jsonPath("$.difference").value(-10.5))
                .andExpect(jsonPath("$.createdAt").value(notNullValue()))
                .andExpect(jsonPath("$.updatedAt").value(notNullValue()))
                .andExpect(jsonPath("$.tenantId").doesNotExist());
    }

    @Test
    void getOpenCashShiftHasNoClosingData() throws Exception {
        Tenant tenant = persistTenant();
        String token = tokenFor(tenant, CASH_READ);
        Fixture fixture = createFixture(tenant.getId());

        UUID shiftId = insertOpenShift(tenant.getId(), fixture.branchA(), fixture.cashierA(), "CAJA-02",
                Instant.now().minus(1, ChronoUnit.HOURS));

        mockMvc.perform(get(BASE_URL + "/" + shiftId).header("Authorization", bearer(token)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("open"))
                .andExpect(jsonPath("$.closedAt").value(nullValue()))
                .andExpect(jsonPath("$.expectedAmount").value(nullValue()))
                .andExpect(jsonPath("$.countedAmount").value(nullValue()))
                .andExpect(jsonPath("$.difference").value(nullValue()));
    }

    @Test
    void getCashShiftByIdNotFoundReturns404() throws Exception {
        Tenant tenant = persistTenant();
        String token = tokenFor(tenant, CASH_READ);

        mockMvc.perform(get(BASE_URL + "/" + UUID.randomUUID()).header("Authorization", bearer(token)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("CASH_SHIFT_NOT_FOUND"))
                .andExpect(jsonPath("$.message").value("Turno de caja no encontrado."));
    }

    @Test
    void scopedUserOnlySeesShiftsOfAllowedBranches() throws Exception {
        Tenant tenant = persistTenant();
        Fixture fixture = createFixture(tenant.getId());
        String token = tokenFor(tenant, CASH_READ, BranchScope.selected, null, List.of(fixture.branchB()));
        Instant now = Instant.now();

        UUID shiftCentro = insertOpenShift(tenant.getId(), fixture.branchA(), fixture.cashierA(), "CAJA-CENTRO",
                now.minus(20, ChronoUnit.MINUTES));
        insertOpenShift(tenant.getId(), fixture.branchB(), fixture.cashierB(), "CAJA-NORTE",
                now.minus(15, ChronoUnit.MINUTES));

        mockMvc.perform(get(BASE_URL).header("Authorization", bearer(token)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].registerCode").value("CAJA-NORTE"));

        // Pedir explícitamente una sucursal fuera del alcance no la expone.
        mockMvc.perform(get(BASE_URL)
                        .param("branchId", fixture.branchA().toString())
                        .header("Authorization", bearer(token)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));

        // Un turno fuera del alcance responde igual que uno inexistente.
        mockMvc.perform(get(BASE_URL + "/" + shiftCentro).header("Authorization", bearer(token)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("CASH_SHIFT_NOT_FOUND"));
    }

    @Test
    void userWithoutAllowedBranchIdsFallsBackToBranchId() throws Exception {
        Tenant tenant = persistTenant();
        Fixture fixture = createFixture(tenant.getId());
        String token = tokenFor(tenant, CASH_READ, BranchScope.assigned, fixture.branchA(), null);
        Instant now = Instant.now();

        insertOpenShift(tenant.getId(), fixture.branchA(), fixture.cashierA(), "CAJA-CENTRO",
                now.minus(20, ChronoUnit.MINUTES));
        insertOpenShift(tenant.getId(), fixture.branchB(), fixture.cashierB(), "CAJA-NORTE",
                now.minus(15, ChronoUnit.MINUTES));

        mockMvc.perform(get(BASE_URL).header("Authorization", bearer(token)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].registerCode").value("CAJA-CENTRO"));
    }

    @Test
    void emptyAllowedBranchIdsMeansNoBranchesEvenWithBranchId() throws Exception {
        Tenant tenant = persistTenant();
        Fixture fixture = createFixture(tenant.getId());
        // Lista vacía a propósito: "ninguna sucursal", no cae al respaldo de branchId.
        String token = tokenFor(tenant, CASH_READ, BranchScope.assigned, fixture.branchA(), List.of());

        insertOpenShift(tenant.getId(), fixture.branchA(), fixture.cashierA(), "CAJA-CENTRO",
                Instant.now().minus(20, ChronoUnit.MINUTES));

        mockMvc.perform(get(BASE_URL).header("Authorization", bearer(token)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));
    }

    @Test
    void tenantIsolationPreventsCrossTenantAccess() throws Exception {
        Tenant tenantA = persistTenant();
        Tenant tenantB = persistTenant();
        String tokenB = tokenFor(tenantB, CASH_READ);
        Fixture fixtureA = createFixture(tenantA.getId());

        UUID shiftA = insertOpenShift(tenantA.getId(), fixtureA.branchA(), fixtureA.cashierA(), "CAJA-TIENDA-A",
                Instant.now().minus(30, ChronoUnit.MINUTES));

        mockMvc.perform(get(BASE_URL).header("Authorization", bearer(tokenB)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));

        mockMvc.perform(get(BASE_URL + "/" + shiftA).header("Authorization", bearer(tokenB)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("CASH_SHIFT_NOT_FOUND"));
    }

    // --- Utilidades ---

    private UUID insertOpenShift(UUID tenantId, UUID branchId, UUID userId, String registerCode, Instant openedAt) {
        return insertShift(tenantId, branchId, userId, registerCode, "open", openedAt,
                new BigDecimal("200.00"), null, null, null, null);
    }

    private UUID insertClosedShift(UUID tenantId, UUID branchId, UUID userId, String registerCode, Instant openedAt) {
        return insertShift(tenantId, branchId, userId, registerCode, "closed", openedAt,
                new BigDecimal("200.00"), openedAt.plus(1, ChronoUnit.HOURS),
                new BigDecimal("900.00"), new BigDecimal("900.00"), BigDecimal.ZERO);
    }

    private UUID insertShift(
            UUID tenantId,
            UUID branchId,
            UUID userId,
            String registerCode,
            String status,
            Instant openedAt,
            BigDecimal openingAmount,
            Instant closedAt,
            BigDecimal expectedAmount,
            BigDecimal countedAmount,
            BigDecimal difference) {
        UUID id = UUID.randomUUID();
        jdbcTemplate.update(
                """
                INSERT INTO cash_shifts (id, tenant_id, branch_id, user_id, register_code, status, opened_at,
                                         opening_amount, closed_at, expected_amount, counted_amount, difference,
                                         created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, now(), now())
                """,
                id,
                tenantId,
                branchId,
                userId,
                registerCode,
                status,
                Timestamp.from(openedAt),
                openingAmount,
                closedAt != null ? Timestamp.from(closedAt) : null,
                expectedAmount,
                countedAmount,
                difference);
        return id;
    }

    private Fixture createFixture(UUID tenantId) {
        UUID branchA = insertBranch(tenantId, "Sucursal Centro", "main");
        UUID branchB = insertBranch(tenantId, "Sucursal Norte", "store");
        UUID cashierA = insertCashier(tenantId, "Cajera Lucía", branchA);
        UUID cashierB = insertCashier(tenantId, "Cajero Andrés", branchB);
        return new Fixture(branchA, branchB, cashierA, cashierB);
    }

    private UUID insertBranch(UUID tenantId, String name, String type) {
        UUID id = UUID.randomUUID();
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        jdbcTemplate.update(
                """
                INSERT INTO branches (id, tenant_id, code, name, type, status)
                VALUES (?, ?, ?, ?, ?, 'active')
                """,
                id, tenantId, "BR-" + suffix, name + " " + suffix, type);
        return id;
    }

    private UUID insertCashier(UUID tenantId, String name, UUID branchId) {
        UUID id = UUID.randomUUID();
        jdbcTemplate.update(
                """
                INSERT INTO users (id, tenant_id, name, email, type, status, branch_id)
                VALUES (?, ?, ?, ?, 'employee', 'active', ?)
                """,
                id, tenantId, name, "cajero-" + id + "@omniretail.local", branchId);
        return id;
    }

    private Tenant persistTenant() {
        String suffix = UUID.randomUUID().toString();
        Tenant tenant = Tenant.builder()
                .name("Tenant " + suffix)
                .slug("tenant-" + suffix)
                .status(TenantStatus.active)
                .defaultCurrency("GTQ")
                .timezone("America/Guatemala")
                .build();
        return tenantRepository.save(tenant);
    }

    private String tokenFor(Tenant tenant, List<String> permissions) {
        return tokenFor(tenant, permissions, BranchScope.all, null, null);
    }

    private String tokenFor(
            Tenant tenant,
            List<String> permissions,
            BranchScope branchScope,
            UUID branchId,
            List<UUID> allowedBranchIds) {
        Role actorRole = Role.builder()
                .name("Rol Actor " + UUID.randomUUID())
                .permissions(permissions)
                .branchScope(branchScope)
                .build();
        actorRole.setTenantId(tenant.getId());
        actorRole = roleRepository.save(actorRole);

        User user = User.builder()
                .name("Empleado Prueba")
                .email("prueba-" + UUID.randomUUID() + "@omniretail.local")
                .type(UserType.employee)
                .roleId(actorRole.getId())
                .branchId(branchId)
                .allowedBranchIds(allowedBranchIds)
                .build();
        user.setTenantId(tenant.getId());
        user = userRepository.save(user);

        Session session = Session.builder()
                .userId(user.getId())
                .expiresAt(Instant.now().plus(1, ChronoUnit.HOURS))
                .build();
        session = sessionRepository.save(session);

        return jwtService.generateToken(user, session);
    }

    private static String bearer(String token) {
        return "Bearer " + token;
    }

    private record Fixture(UUID branchA, UUID branchB, UUID cashierA, UUID cashierB) {}
}
