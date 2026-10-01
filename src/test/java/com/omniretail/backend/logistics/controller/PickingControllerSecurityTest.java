package com.omniretail.backend.logistics.controller;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
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
class PickingControllerSecurityTest {

    private static final String BASE = "/api/v1/logistics/picking";

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
    void queueRequiresReadPermission() throws Exception {
        Actor actor = actor(List.of());
        mvc.perform(get(BASE)
                        .param("branchId", actor.branchId().toString())
                        .header("Authorization", "Bearer " + actor.token()))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("ACCESS_DENIED"));
    }

    @Test
    void authorizedQueueIsTenantAndBranchScoped() throws Exception {
        Actor actor = actor(List.of("logistics.picking.read"));
        mvc.perform(get(BASE)
                        .param("branchId", actor.branchId().toString())
                        .header("Authorization", "Bearer " + actor.token()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray())
                .andExpect(jsonPath("$.length()").value(0));
    }

    private Actor actor(List<String> permissions) {
        String suffix = UUID.randomUUID().toString();
        Tenant tenant = tenants.saveAndFlush(Tenant.builder()
                .name("Tenant " + suffix)
                .slug("picking-security-" + suffix)
                .status(TenantStatus.active)
                .defaultCurrency("GTQ")
                .timezone("America/Guatemala")
                .build());
        UUID branch = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO branches (id, tenant_id, code, name, type, status)
                VALUES (?, ?, ?, 'Principal', 'main', 'active')
                """, branch, tenant.getId(), "BR-" + suffix.substring(0, 8));
        Role role = Role.builder().name("Picker " + suffix).permissions(permissions).build();
        role.setTenantId(tenant.getId());
        role = roles.save(role);
        User user = User.builder()
                .name("Picker")
                .email("picker-" + suffix + "@test.local")
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

    private record Actor(UUID branchId, String token) {}
}
