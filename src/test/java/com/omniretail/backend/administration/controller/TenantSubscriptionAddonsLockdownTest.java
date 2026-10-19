package com.omniretail.backend.administration.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
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

/**
 * Un administrador de empresa con TODOS los permisos (incluido {@code admin.plans.manage}, que el propio rol puede
 * delegarse) no puede cambiar los complementos por HTTP directo, y la consulta informativa no escribe nada.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(TestcontainersConfiguration.class)
class TenantSubscriptionAddonsLockdownTest {

    private static final String SUBSCRIPTIONS = "/api/v1/admin/subscriptions";

    @Autowired private MockMvc mockMvc;
    @Autowired private JwtService jwtService;
    @Autowired private JdbcTemplate jdbc;
    @MockitoBean private SessionService sessionService;
    @MockitoBean private PermissionResolver permissionResolver;
    @MockitoBean private TenantEntitlementResolver entitlementResolver;
    @MockitoBean private BranchAccessResolver branchAccessResolver;

    @BeforeEach
    void setUp() {
        given(sessionService.isActive(any(), any())).willReturn(true);
        given(permissionResolver.hasPermission(any(UUID.class), any(UUID.class), anyString())).willReturn(true);
        given(entitlementResolver.resolve(any(UUID.class)))
                .willReturn(new TenantEntitlements(true, true, EnumSet.allOf(SaasCapability.class)));
        given(branchAccessResolver.resolve(any())).willReturn(new BranchAccess(true, Set.of()));
    }

    @Test
    void aTenantAdministratorCannotChangeAddonsThroughTheOldEndpoint() throws Exception {
        UUID tenant = tenantWithBasicPlan();

        mockMvc.perform(put(SUBSCRIPTIONS + "/addons")
                        .header("Authorization", token(tenant))
                        .contentType(APPLICATION_JSON)
                        .content("{\"addonCodes\":[\"ecommerce_delivery\",\"advanced_reports\"]}"))
                .andExpect(status().is4xxClientError());

        assertThat(addons(tenant)).isEmpty();
    }

    @Test
    void noOtherWriteMethodOnTheSubscriptionResourceExists() throws Exception {
        UUID tenant = tenantWithBasicPlan();
        String authorization = token(tenant);

        for (String method : new String[] {"POST", "PUT", "PATCH", "DELETE"}) {
            mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                            .request(org.springframework.http.HttpMethod.valueOf(method), SUBSCRIPTIONS)
                            .header("Authorization", authorization)
                            .contentType(APPLICATION_JSON)
                            .content("{\"addonCodes\":[\"advanced_reports\"]}"))
                    .andExpect(status().is4xxClientError());
        }
        assertThat(addons(tenant)).isEmpty();
    }

    @Test
    void theInformativeQueryStillWorksAndWritesNothing() throws Exception {
        UUID tenant = tenantWithBasicPlan();
        long invoicesBefore = count("SELECT COUNT(*) FROM subscription_invoices WHERE tenant_id = ?", tenant);
        Instant periodBefore = jdbc.queryForObject(
                "SELECT current_period_end FROM tenant_subscriptions WHERE tenant_id = ?",
                java.sql.Timestamp.class, tenant).toInstant();

        for (int i = 0; i < 2; i++) {
            mockMvc.perform(get(SUBSCRIPTIONS).header("Authorization", token(tenant)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.tenantId").value(tenant.toString()))
                    .andExpect(jsonPath("$.addonCodes").isEmpty());
        }

        assertThat(count("SELECT COUNT(*) FROM subscription_invoices WHERE tenant_id = ?", tenant))
                .isEqualTo(invoicesBefore);
        assertThat(jdbc.queryForObject(
                        "SELECT current_period_end FROM tenant_subscriptions WHERE tenant_id = ?",
                        java.sql.Timestamp.class, tenant).toInstant())
                .isEqualTo(periodBefore);
    }

    private UUID tenantWithBasicPlan() {
        UUID tenant = UUID.randomUUID();
        jdbc.update(
                "INSERT INTO tenants (id, name, slug, status, default_currency, timezone) "
                        + "VALUES (?, ?, ?, 'active', 'GTQ', 'America/Guatemala')",
                tenant, "Tenant " + tenant, "tenant-" + tenant);
        jdbc.update("""
                INSERT INTO tenant_subscriptions
                    (tenant_id, plan_id, status, started_at, current_period_start, current_period_end, addon_codes)
                SELECT ?, id, 'active', now(), now(), now() + INTERVAL '1 month', ARRAY[]::text[]
                FROM saas_plans WHERE code = 'basic'
                """, tenant);
        return tenant;
    }

    private String addons(UUID tenant) {
        return jdbc.queryForObject(
                "SELECT array_to_string(addon_codes, ',') FROM tenant_subscriptions WHERE tenant_id = ?",
                String.class, tenant);
    }

    private long count(String sql, Object... args) {
        Long value = jdbc.queryForObject(sql, Long.class, args);
        return value == null ? 0 : value;
    }

    private String token(UUID tenant) {
        User user = User.builder()
                .name("Administrador de empresa")
                .email("admin-" + UUID.randomUUID() + "@test.local")
                .type(UserType.employee)
                .roleId(UUID.randomUUID())
                .branchId(UUID.randomUUID())
                .build();
        ReflectionTestUtils.setField(user, "id", UUID.randomUUID());
        user.setTenantId(tenant);
        Session session = Session.builder()
                .id(UUID.randomUUID())
                .userId(user.getId())
                .expiresAt(Instant.now().plus(1, ChronoUnit.HOURS))
                .build();
        return "Bearer " + jwtService.generateToken(user, session);
    }
}
