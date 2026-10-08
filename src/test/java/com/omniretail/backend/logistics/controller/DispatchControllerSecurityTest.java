package com.omniretail.backend.logistics.controller;

import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.omniretail.backend.TestcontainersConfiguration;
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
import com.omniretail.backend.logistics.service.DispatchTestFixture;
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
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(TestcontainersConfiguration.class)
@Transactional
class DispatchControllerSecurityTest {

    private static final String BASE = "/api/v1/logistics/dispatch";

    @Autowired private MockMvc mvc;
    @Autowired private TenantRepository tenants;
    @Autowired private RoleRepository roles;
    @Autowired private UserRepository users;
    @Autowired private SessionRepository sessions;
    @Autowired private JwtService jwtService;
    @Autowired private JdbcTemplate jdbc;

    @Test
    void queueRequiresAuthentication() throws Exception {
        mvc.perform(get(BASE).param("branchId", UUID.randomUUID().toString()))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void preparedDetailRequiresAuthentication() throws Exception {
        mvc.perform(get(BASE + "/{orderId}/prepared", UUID.randomUUID())
                        .param("branchId", UUID.randomUUID().toString()))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void queueRequiresReadPermission() throws Exception {
        Actor actor = actor(List.of());
        mvc.perform(get(BASE)
                        .param("branchId", actor.branchId().toString())
                        .header("Authorization", "Bearer " + actor.token()))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("ACCESS_DENIED"));
    }

    @Test
    void preparedDetailRequiresReadPermission() throws Exception {
        Actor actor = actor(List.of());
        mvc.perform(get(BASE + "/{orderId}/prepared", UUID.randomUUID())
                        .param("branchId", actor.branchId().toString())
                        .header("Authorization", "Bearer " + actor.token()))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("ACCESS_DENIED"));
    }

    @Test
    void preparedDetailReturnsAuthorizedPreparationContract() throws Exception {
        DispatchTestFixture.Data fixture = DispatchTestFixture.create(jdbc, 2);
        Actor actor = actorForFixture(fixture, List.of("logistics.dispatch.read"));
        UUID pickingOrderId = jdbc.queryForObject(
                "SELECT id FROM picking_orders WHERE order_id = ?", UUID.class, fixture.orderId());

        mvc.perform(get(BASE + "/{orderId}/prepared", fixture.orderId())
                        .param("branchId", fixture.branchId().toString())
                        .header("Authorization", "Bearer " + actor.token()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.orderId").value(fixture.orderId().toString()))
                .andExpect(jsonPath("$.orderReference").value("WEB-" + fixture.orderId()))
                .andExpect(jsonPath("$.orderStatus").value("ready_for_dispatch"))
                .andExpect(jsonPath("$.recipientName").value("Ana Lopez"))
                .andExpect(jsonPath("$.recipientPhone").value("+502 5555-5555"))
                .andExpect(jsonPath("$.deliveryAddress.line1").value("Zona 1"))
                .andExpect(jsonPath("$.transportMode").value("third_party"))
                .andExpect(jsonPath("$.pickingOrderId").value(pickingOrderId.toString()))
                .andExpect(jsonPath("$.pickingStatus").value("completed"))
                .andExpect(jsonPath("$.pickingCompletedAt").isNotEmpty())
                .andExpect(jsonPath("$.packingId").value(fixture.packingId().toString()))
                .andExpect(jsonPath("$.packingStatus").value("finalized"))
                .andExpect(jsonPath("$.packingFinalizedAt").isNotEmpty())
                .andExpect(jsonPath("$.packageCount").value(fixture.packageCount()))
                .andExpect(jsonPath("$.totalWeight").value(4.5))
                .andExpect(jsonPath("$.labelCode").value(fixture.labelCode()));
    }

    @Test
    void readPermissionAllowsTenantAndBranchScopedQueue() throws Exception {
        Actor actor = actor(List.of("logistics.dispatch.read"));
        mvc.perform(get(BASE)
                        .param("branchId", actor.branchId().toString())
                        .header("Authorization", "Bearer " + actor.token()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray())
                .andExpect(jsonPath("$.length()").value(0));
    }

    @Test
    void readPermissionDoesNotAuthorizeConfirmation() throws Exception {
        Actor actor = actor(List.of("logistics.dispatch.read"));
        mvc.perform(confirmRequest(actor))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("ACCESS_DENIED"));
    }

    @Test
    void confirmPermissionIsIndependentFromReadPermission() throws Exception {
        Actor actor = actor(List.of("logistics.dispatch.confirm"));
        mvc.perform(get(BASE)
                        .param("branchId", actor.branchId().toString())
                        .header("Authorization", "Bearer " + actor.token()))
                .andExpect(status().isForbidden());
        mvc.perform(confirmRequest(actor))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("ORDER_NOT_FOUND"));
    }

    private org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder confirmRequest(
            Actor actor) {
        return post(BASE + "/{orderId}/confirm", UUID.randomUUID())
                .param("branchId", actor.branchId().toString())
                .header("Authorization", "Bearer " + actor.token())
                .contentType(APPLICATION_JSON)
                .content("""
                        {"operationId":"security-confirm","carrierName":"Carrier",
                         "trackingNumber":"TRACK","packages":null}
                        """);
    }

    private Actor actor(List<String> permissions) {
        String suffix = UUID.randomUUID().toString();
        Tenant tenant = tenants.saveAndFlush(Tenant.builder()
                .name("Tenant " + suffix)
                .slug("dispatch-security-" + suffix)
                .status(TenantStatus.active)
                .defaultCurrency("GTQ")
                .timezone("America/Guatemala")
                .build());
        UUID branch = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO branches (id, tenant_id, code, name, type, status)
                VALUES (?, ?, ?, 'Principal', 'main', 'active')
                """, branch, tenant.getId(), "BR-" + suffix.substring(0, 8));
        Role role = Role.builder().name("Dispatch " + suffix).permissions(permissions).build();
        role.setTenantId(tenant.getId());
        role = roles.save(role);
        User user = User.builder()
                .name("Despachador")
                .email("dispatch-" + suffix + "@test.local")
                .type(UserType.employee)
                .roleId(role.getId())
                .branchId(branch)
                .build();
        user.setTenantId(tenant.getId());
        user = users.save(user);
        Session session = sessions.save(Session.builder()
                .userId(user.getId())
                .expiresAt(Instant.now().plus(1, ChronoUnit.HOURS))
                .build());
        return new Actor(branch, jwtService.generateToken(user, session));
    }

    private Actor actorForFixture(
            DispatchTestFixture.Data fixture, List<String> permissions) {
        Role role = Role.builder()
                .name("Dispatch fixture " + UUID.randomUUID())
                .permissions(permissions)
                .build();
        role.setTenantId(fixture.tenantId());
        role = roles.saveAndFlush(role);
        jdbc.update(
                "UPDATE users SET role_id = ? WHERE tenant_id = ? AND id = ?",
                role.getId(), fixture.tenantId(), fixture.userId());
        User user = users.findByTenantIdAndId(fixture.tenantId(), fixture.userId()).orElseThrow();
        Session session = sessions.save(Session.builder()
                .userId(user.getId())
                .expiresAt(Instant.now().plus(1, ChronoUnit.HOURS))
                .build());
        return new Actor(fixture.branchId(), jwtService.generateToken(user, session));
    }

    private record Actor(UUID branchId, String token) {}
}
