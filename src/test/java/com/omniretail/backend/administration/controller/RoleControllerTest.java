package com.omniretail.backend.administration.controller;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
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
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(TestcontainersConfiguration.class)
class RoleControllerTest {

    private static final String BASE_URL = "/api/v1/administration/roles";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JwtService jwtService;

    @Autowired
    private TenantRepository tenantRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private SessionRepository sessionRepository;

    @Autowired
    private RoleRepository roleRepository;

    @Test
    void withoutTokenReturnsUnauthorized() throws Exception {
        mockMvc.perform(get(BASE_URL)).andExpect(status().isUnauthorized());
    }

    @Test
    void createRoleSuccess() throws Exception {
        Tenant tenantA = persistTenant();
        String token = tokenFor(tenantA);

        String body = """
                {"name":"Soporte","permissions":["admin.branches.read","admin.branches.manage"]}
                """;

        mockMvc.perform(post(BASE_URL)
                        .header("Authorization", bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.name").value("Soporte"))
                .andExpect(jsonPath("$.isSystem").value(false))
                .andExpect(jsonPath("$.tenantId").value(tenantA.getId().toString()))
                .andExpect(jsonPath("$.permissions[0]").value("admin.branches.read"))
                .andExpect(jsonPath("$.permissions[1]").value("admin.branches.manage"));
    }

    @Test
    void createRoleDuplicateNameConflict() throws Exception {
        Tenant tenantA = persistTenant();
        String token = tokenFor(tenantA);

        String body = """
                {"name":"Soporte"}
                """;

        mockMvc.perform(post(BASE_URL)
                        .header("Authorization", bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isCreated());

        mockMvc.perform(post(BASE_URL)
                        .header("Authorization", bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("ROLE_NAME_EXISTS"));
    }

    @Test
    void systemRoleCannotBeModifiedOrArchived() throws Exception {
        Tenant tenantA = persistTenant();
        String token = tokenFor(tenantA);

        Role systemRole = Role.builder().name("Administrador").isSystem(true).build();
        systemRole.setTenantId(tenantA.getId());
        systemRole = roleRepository.save(systemRole);

        String updateBody = """
                {"name":"Administrador Editado"}
                """;

        mockMvc.perform(put(BASE_URL + "/" + systemRole.getId())
                        .header("Authorization", bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(updateBody))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("CANNOT_MODIFY_SYSTEM_ROLE"));

        mockMvc.perform(delete(BASE_URL + "/" + systemRole.getId()).header("Authorization", bearer(token)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("CANNOT_ARCHIVE_SYSTEM_ROLE"));
    }

    @Test
    void crossTenantIsolation() throws Exception {
        Tenant tenantA = persistTenant();
        Tenant tenantB = persistTenant();
        String tokenB = tokenFor(tenantB);

        Role role = Role.builder().name("Soporte").build();
        role.setTenantId(tenantA.getId());
        role = roleRepository.save(role);

        mockMvc.perform(get(BASE_URL + "/" + role.getId()).header("Authorization", bearer(tokenB)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("ROLE_NOT_FOUND"));

        String updateBody = """
                {"name":"Soporte Editado"}
                """;

        mockMvc.perform(put(BASE_URL + "/" + role.getId())
                        .header("Authorization", bearer(tokenB))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(updateBody))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("ROLE_NOT_FOUND"));
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

    private String tokenFor(Tenant tenant) {
        User user = User.builder()
                .name("Empleado Demo")
                .email("empleado-" + UUID.randomUUID() + "@omniretail.local")
                .type(UserType.employee)
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
}
