package com.omniretail.backend.inventory.controller;

import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.omniretail.backend.SubscriptionTestFixtures;
import com.omniretail.backend.TestcontainersConfiguration;
import com.omniretail.backend.administration.entity.Role;
import com.omniretail.backend.administration.entity.Tenant;
import com.omniretail.backend.administration.entity.TenantStatus;
import com.omniretail.backend.administration.entity.User;
import com.omniretail.backend.administration.entity.UserType;
import com.omniretail.backend.administration.repository.RoleRepository;
import com.omniretail.backend.administration.repository.SaasPlanRepository;
import com.omniretail.backend.administration.repository.TenantRepository;
import com.omniretail.backend.administration.repository.TenantSubscriptionRepository;
import com.omniretail.backend.administration.repository.UserRepository;
import com.omniretail.backend.auth.entity.Session;
import com.omniretail.backend.auth.repository.SessionRepository;
import com.omniretail.backend.auth.service.JwtService;
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
class InventoryTransferControllerSecurityTest {

    private static final String REQUESTS = "/api/v1/inventory/transfer-requests";
    private static final String TRANSFERS = "/api/v1/inventory/transfers";
    private static final String MANAGE = "inventory.transfers.manage";

    @Autowired private MockMvc mvc;
    @Autowired private TenantRepository tenants;
    @Autowired private RoleRepository roles;
    @Autowired private UserRepository users;
    @Autowired private SessionRepository sessions;
    @Autowired private TenantSubscriptionRepository subscriptions;
    @Autowired private SaasPlanRepository plans;
    @Autowired private JwtService jwtService;
    @Autowired private JdbcTemplate jdbc;

    @Test
    void transferRequestAndTransferEndpointsRequireAuthentication() throws Exception {
        mvc.perform(get(REQUESTS)).andExpect(status().isUnauthorized());
        mvc.perform(get(TRANSFERS)).andExpect(status().isUnauthorized());
        mvc.perform(post(REQUESTS).contentType(APPLICATION_JSON).content("{}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void validJwtWithoutManagePermissionIsForbiddenForBothControllers() throws Exception {
        Actor actor = actor(List.of());

        mvc.perform(get(REQUESTS).header("Authorization", "Bearer " + actor.token()))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("ACCESS_DENIED"));
        mvc.perform(get(TRANSFERS).header("Authorization", "Bearer " + actor.token()))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("ACCESS_DENIED"));
    }

    @Test
    void managePermissionAuthorizesBothListEndpoints() throws Exception {
        Actor actor = actor(List.of(MANAGE));

        mvc.perform(get(REQUESTS).header("Authorization", "Bearer " + actor.token()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items").isArray())
                .andExpect(jsonPath("$.totalItems").value(0));
        mvc.perform(get(TRANSFERS).header("Authorization", "Bearer " + actor.token()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items").isArray())
                .andExpect(jsonPath("$.totalItems").value(0));
    }

    @Test
    void managePermissionPassesSecurityForMutationMethodsOfBothControllers() throws Exception {
        Actor actor = actor(List.of(MANAGE));
        UUID requestId = UUID.randomUUID();
        UUID transferId = UUID.randomUUID();

        mvc.perform(post(REQUESTS + "/{id}/approve", requestId)
                        .header("Authorization", "Bearer " + actor.token())
                        .contentType(APPLICATION_JSON)
                        .content("{\"operationId\":\"security-approve\"}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("INVENTORY_TRANSFER_REQUEST_NOT_FOUND"));
        mvc.perform(post(TRANSFERS + "/{id}/receipts", transferId)
                        .header("Authorization", "Bearer " + actor.token())
                        .contentType(APPLICATION_JSON)
                        .content("""
                                {"confirmationId":"security-receipt",
                                 "destinationLocationId":"%s",
                                 "items":[{"itemId":"%s","receivedQuantity":1}]}
                                """.formatted(UUID.randomUUID(), UUID.randomUUID())))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("INVENTORY_TRANSFER_NOT_FOUND"));
    }

    private Actor actor(List<String> permissions) {
        String suffix = UUID.randomUUID().toString();
        Tenant tenant = tenants.saveAndFlush(Tenant.builder()
                .name("Tenant " + suffix)
                .slug("transfer-security-" + suffix)
                .status(TenantStatus.active)
                .defaultCurrency("GTQ")
                .timezone("America/Guatemala")
                .build());
        SubscriptionTestFixtures.provisionBasic(subscriptions, plans, tenant.getId());
        UUID branchId = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO branches (id, tenant_id, code, name, type, status)
                VALUES (?, ?, ?, 'Principal', 'main', 'active')
                """, branchId, tenant.getId(), "BR-" + suffix.substring(0, 8));
        Role role = Role.builder()
                .name("Transfer " + suffix)
                .permissions(permissions)
                .build();
        role.setTenantId(tenant.getId());
        role = roles.save(role);
        User user = User.builder()
                .name("Operador transferencias")
                .email("transfer-" + suffix + "@test.local")
                .type(UserType.employee)
                .roleId(role.getId())
                .branchId(branchId)
                .build();
        user.setTenantId(tenant.getId());
        user = users.save(user);
        Session session = sessions.save(Session.builder()
                .userId(user.getId())
                .activeBranchId(branchId)
                .expiresAt(Instant.now().plus(1, ChronoUnit.HOURS))
                .build());
        return new Actor(jwtService.generateToken(user, session));
    }

    private record Actor(String token) {}
}
