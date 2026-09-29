package com.omniretail.backend.pos.controller;

import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.omniretail.backend.TestcontainersConfiguration;
import com.omniretail.backend.administration.entity.Role;
import com.omniretail.backend.administration.entity.RoleStatus;
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
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

/** Integration coverage for the POS sale boundary. Requires Docker for PostgreSQL Testcontainers. */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(TestcontainersConfiguration.class)
class SaleControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired private TenantRepository tenantRepository;
    @Autowired private RoleRepository roleRepository;
    @Autowired private UserRepository userRepository;
    @Autowired private SessionRepository sessionRepository;
    @Autowired private JwtService jwtService;

    @Test
    void requiresAuthenticationAndPermission() throws Exception {
        mockMvc.perform(post("/api/v1/pos/sales")
                        .contentType(APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void salesReadAndVoidEndpointsRequireAuthentication() throws Exception {
        String saleId = "11111111-1111-1111-1111-111111111111";
        String branchId = "22222222-2222-2222-2222-222222222222";

        mockMvc.perform(get("/api/v1/pos/sales").param("branchId", branchId))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/v1/pos/sales/{id}", saleId))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(post("/api/v1/pos/sales/{id}/void", saleId))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void salesReadAndVoidEndpointsRequireTheirPermissions() throws Exception {
        String token = tokenWithoutPosPermissions();
        String saleId = "11111111-1111-1111-1111-111111111111";
        String branchId = "22222222-2222-2222-2222-222222222222";

        mockMvc.perform(get("/api/v1/pos/sales").param("branchId", branchId)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/api/v1/pos/sales/{id}", saleId)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isForbidden());
        mockMvc.perform(post("/api/v1/pos/sales/{id}/void", saleId)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isForbidden());
    }

    private String tokenWithoutPosPermissions() {
        Tenant tenant = tenantRepository.save(Tenant.builder()
                .name("Tienda " + UUID.randomUUID())
                .slug("tienda-" + UUID.randomUUID())
                .status(TenantStatus.active)
                .defaultCurrency("GTQ")
                .timezone("America/Guatemala")
                .build());
        Role role = Role.builder().name("Rol " + UUID.randomUUID())
                .status(RoleStatus.active).permissions(List.of("catalog.products.read")).build();
        role.setTenantId(tenant.getId());
        role = roleRepository.save(role);
        User user = User.builder().name("Empleado").email("user-" + UUID.randomUUID() + "@test.local")
                .type(UserType.employee).roleId(role.getId()).build();
        user.setTenantId(tenant.getId());
        user = userRepository.save(user);
        Session session = sessionRepository.save(Session.builder().userId(user.getId())
                .expiresAt(Instant.now().plus(1, ChronoUnit.HOURS).truncatedTo(ChronoUnit.SECONDS)).build());
        return jwtService.generateToken(user, session);
    }
}
