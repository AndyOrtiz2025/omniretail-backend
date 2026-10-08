package com.omniretail.backend.pos.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
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
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(TestcontainersConfiguration.class)
class CashShiftControllerTest {

    private static final String BASE = "/api/v1/pos/cash-shifts";
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
    void opensClosesAndCanReopen() throws Exception {
        Fixture f = fixture();
        open(f, openBody(f)).andExpect(status().isCreated())
                .andExpect(jsonPath("$.userId").value(f.user().toString()))
                .andExpect(jsonPath("$.status").value("open"));
        UUID id = shiftId(f);
        movement(f, id, "in", "40.00");
        movement(f, id, "out", "15.00");
        close(f, id, "120.00").andExpect(status().isOk())
                .andExpect(jsonPath("$.expectedAmount").value(125.00))
                .andExpect(jsonPath("$.difference").value(-5.00))
                .andExpect(jsonPath("$.status").value("closed_with_difference"))
                .andExpect(jsonPath("$.closedAt").isNotEmpty());
        close(f, id, "125.00").andExpect(status().isConflict());
        open(f, openBody(f)).andExpect(status().isCreated());
    }

    @Test
    void returnsCurrentUsersOpenShiftOrNoContent() throws Exception {
        Fixture fixture = fixture();
        openShift(fixture).andExpect(status().isNoContent());

        open(fixture, openBody(fixture)).andExpect(status().isCreated());

        openShift(fixture)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.userId").value(fixture.user().toString()))
                .andExpect(jsonPath("$.branchId").value(fixture.branch().toString()))
                .andExpect(jsonPath("$.status").value("open"));
    }

    @Test
    void openShiftLookupDoesNotExposeAnotherCashiersShift() throws Exception {
        Fixture fixture = fixture();
        Fixture other = addUser(fixture.tenant(), fixture.branch());
        open(other, openBody(other)).andExpect(status().isCreated());

        openShift(fixture).andExpect(status().isNoContent());
    }

    @Test
    void openShiftLookupRejectsForeignOrUnauthorizedBranch() throws Exception {
        Fixture fixture = fixture();
        Fixture foreign = fixture();
        openShift(fixture, foreign.branch()).andExpect(status().isNotFound());
        Fixture unassigned = addUser(fixture.tenant(), null);
        openShift(unassigned, fixture.branch()).andExpect(status().isNotFound());
    }

    @Test
    void openShiftLookupRequiresAuthenticationReadPermissionAndPosCapability() throws Exception {
        Fixture fixture = fixture();
        mvc.perform(get(BASE + "/open").param("branchId", fixture.branch().toString()))
                .andExpect(status().isUnauthorized());

        given(permissions.hasPermission(any(), any(), eq("pos.cash.read"))).willReturn(false);
        openShift(fixture).andExpect(status().isForbidden());
        given(permissions.hasPermission(any(), any(), eq("pos.cash.read"))).willReturn(true);
        given(entitlements.resolve(fixture.tenant())).willReturn(
                new TenantEntitlements(true, true, EnumSet.of(SaasCapability.inventory)));
        openShift(fixture)
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("CAPABILITY_REQUIRED"));
    }

    @Test
    void closesExactlyWithoutMovements() throws Exception {
        Fixture f = fixture();
        open(f, openBody(f)).andExpect(status().isCreated());
        close(f, shiftId(f), "100.00").andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("closed"))
                .andExpect(jsonPath("$.difference").value(0));
    }

    @Test
    void duplicateOpenOnDifferentRegisterIsConflict() throws Exception {
        Fixture f = fixture();
        open(f, openBody(f)).andExpect(status().isCreated());
        open(f, openBody(f).replace("CAJA-1", "CAJA-2")).andExpect(status().isConflict());
        assertThat(openCount(f)).isOne();
    }

    @Test
    void simultaneousOpenRequestsCreateExactlyOneShift() throws Exception {
        Fixture f = fixture();
        assertThat(concurrently(() -> open(f, openBody(f)).andReturn().getResponse().getStatus()))
                .containsExactlyInAnyOrder(201, 409);
        assertThat(openCount(f)).isOne();
    }

    @Test
    void simultaneousCloseRequestsCannotOverwriteOneAnother() throws Exception {
        Fixture f = fixture();
        open(f, openBody(f)).andExpect(status().isCreated());
        UUID id = shiftId(f);
        assertThat(concurrently(() -> close(f, id, "100.00").andReturn().getResponse().getStatus()))
                .containsExactlyInAnyOrder(200, 409);
        assertThat(openCount(f)).isZero();
    }

    @Test
    void differentCashiersInSameBranchCanEachOpen() throws Exception {
        Fixture f = fixture();
        Fixture other = addUser(f.tenant(), f.branch());
        open(f, openBody(f)).andExpect(status().isCreated());
        open(other, openBody(other)).andExpect(status().isCreated());
        close(other, shiftId(f), "100.00").andExpect(status().isNotFound());
        assertThat(openCount(f)).isOne();
    }

    @Test
    void rejectsCrossTenantAndUnassignedBranchesAndShifts() throws Exception {
        Fixture f = fixture();
        Fixture other = fixture();
        open(other, openBody(other)).andExpect(status().isCreated());
        open(f, openBody(other)).andExpect(status().isNotFound());
        close(f, shiftId(other), "100.00").andExpect(status().isNotFound());
        Fixture unassigned = addUser(f.tenant(), null);
        open(unassigned, openBody(f)).andExpect(status().isNotFound());
        assertThat(openCount(other)).isOne();
    }

    @Test
    void bodyCannotOverrideAuthenticatedIdentity() throws Exception {
        Fixture f = fixture();
        String body = openBody(f).replace("}", ",\"tenantId\":\"" + UUID.randomUUID()
                + "\",\"userId\":\"" + UUID.randomUUID() + "\"}");
        open(f, body).andExpect(status().isCreated())
                .andExpect(jsonPath("$.userId").value(f.user().toString()));
        assertThat(openCount(f)).isOne();
    }

    @Test
    void requiresAuthenticationForBothOperations() throws Exception {
        for (String action : List.of("open", "close")) {
            mvc.perform(post(BASE + "/" + action).contentType(APPLICATION_JSON).content("{}"))
                    .andExpect(status().isUnauthorized());
        }
    }

    @Test
    void requiresSeparateOpenAndClosePermissions() throws Exception {
        Fixture f = fixture();
        given(permissions.hasPermission(any(), any(), eq("pos.cash.open"))).willReturn(false);
        open(f, openBody(f)).andExpect(status().isForbidden());
        given(permissions.hasPermission(any(), any(), eq("pos.cash.open"))).willReturn(true);
        open(f, openBody(f)).andExpect(status().isCreated());
        given(permissions.hasPermission(any(), any(), eq("pos.cash.close"))).willReturn(false);
        close(f, shiftId(f), "100.00").andExpect(status().isForbidden());
        assertThat(openCount(f)).isOne();
    }

    @Test
    void requiresPosCapabilityForBothOperations() throws Exception {
        Fixture f = fixture();
        open(f, openBody(f)).andExpect(status().isCreated());
        given(entitlements.resolve(f.tenant())).willReturn(
                new TenantEntitlements(true, true, EnumSet.of(SaasCapability.inventory)));
        open(f, openBody(f)).andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("CAPABILITY_REQUIRED"));
        close(f, shiftId(f), "100.00").andExpect(status().isForbidden());
        assertThat(openCount(f)).isOne();
    }

    private List<Integer> concurrently(Callable<Integer> call) throws Exception {
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            Callable<Integer> task = () -> {
                ready.countDown();
                if (!start.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("Inicio agotado");
                return call.call();
            };
            var first = executor.submit(task);
            var second = executor.submit(task);
            try {
                assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
                start.countDown();
                return List.of(first.get(30, TimeUnit.SECONDS), second.get(30, TimeUnit.SECONDS));
            } finally {
                start.countDown();
                executor.shutdownNow();
            }
        }
    }

    private Fixture fixture() {
        UUID tenant = UUID.randomUUID();
        UUID branch = UUID.randomUUID();
        jdbc.update("INSERT INTO tenants (id, name, slug) VALUES (?, 'POS test', ?)", tenant, "pos-" + tenant);
        jdbc.update("""
                INSERT INTO branches (id, tenant_id, code, name, type, status)
                VALUES (?, ?, 'B1', 'Sucursal POS', 'store', 'active')
                """, branch, tenant);
        return addUser(tenant, branch);
    }

    private Fixture addUser(UUID tenant, UUID branch) {
        UUID user = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO users (id, tenant_id, name, email, type, status, branch_id)
                VALUES (?, ?, 'Cajero', ?, 'employee', 'active', ?)
                """, user, tenant, user + "@test.local", branch);
        return new Fixture(tenant, branch, user);
    }

    private void movement(Fixture f, UUID shift, String type, String amount) {
        jdbc.update("""
                INSERT INTO cash_movements
                    (id, tenant_id, cash_shift_id, type, amount, reason, created_by_user_id)
                VALUES (?, ?, ?, ?, ?::numeric, 'Prueba', ?)
                """, UUID.randomUUID(), f.tenant(), shift, type, amount, f.user());
    }

    private UUID shiftId(Fixture f) {
        return jdbc.queryForObject("SELECT id FROM cash_shifts WHERE tenant_id = ? AND user_id = ? AND status = 'open'",
                UUID.class, f.tenant(), f.user());
    }

    private long openCount(Fixture f) {
        return jdbc.queryForObject("SELECT count(*) FROM cash_shifts WHERE tenant_id = ? AND user_id = ? AND status = 'open'",
                Long.class, f.tenant(), f.user());
    }

    private String openBody(Fixture f) {
        return """
                {"branchId":"%s","registerCode":"CAJA-1","openingAmount":100.00}
                """.formatted(f.branch());
    }

    private ResultActions open(Fixture f, String body) throws Exception {
        return mvc.perform(post(BASE + "/open").header("Authorization", token(f))
                .contentType(APPLICATION_JSON).content(body));
    }

    private ResultActions close(Fixture f, UUID id, String counted) throws Exception {
        return mvc.perform(post(BASE + "/close").header("Authorization", token(f))
                .contentType(APPLICATION_JSON).content("""
                        {"cashShiftId":"%s","countedAmount":%s}
                        """.formatted(id, counted)));
    }

    private ResultActions openShift(Fixture fixture) throws Exception {
        return openShift(fixture, fixture.branch());
    }

    private ResultActions openShift(Fixture fixture, UUID branchId) throws Exception {
        return mvc.perform(get(BASE + "/open")
                .param("branchId", branchId.toString())
                .header("Authorization", token(fixture)));
    }

    private String token(Fixture f) {
        User user = User.builder().name("Cajero").email("pos@test.local").type(UserType.employee)
                .roleId(UUID.randomUUID()).branchId(f.branch()).build();
        user.setTenantId(f.tenant());
        ReflectionTestUtils.setField(user, "id", f.user());
        Session session = Session.builder().id(UUID.randomUUID()).userId(f.user())
                .expiresAt(Instant.now().plusSeconds(3600)).build();
        return "Bearer " + jwtService.generateToken(user, session);
    }

    private record Fixture(UUID tenant, UUID branch, UUID user) {}
}
