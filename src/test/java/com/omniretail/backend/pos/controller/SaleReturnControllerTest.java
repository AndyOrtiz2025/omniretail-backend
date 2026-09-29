package com.omniretail.backend.pos.controller;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.omniretail.backend.TestcontainersConfiguration;
import com.omniretail.backend.administration.entity.*;
import com.omniretail.backend.administration.repository.*;
import com.omniretail.backend.auth.entity.Session;
import com.omniretail.backend.auth.repository.SessionRepository;
import com.omniretail.backend.auth.service.JwtService;
import java.time.*;
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

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(TestcontainersConfiguration.class)
class SaleReturnControllerTest {
    @Autowired private MockMvc mockMvc;
    @Autowired private TenantRepository tenants;
    @Autowired private RoleRepository roles;
    @Autowired private UserRepository users;
    @Autowired private SessionRepository sessions;
    @Autowired private JwtService jwt;

    @Test
    void requiresAuthenticationForCreateAndList() throws Exception {
        UUID saleId = UUID.randomUUID();
        mockMvc.perform(post("/api/v1/pos/sales/{id}/returns", saleId)
                        .contentType("application/json").content("{}"))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/v1/pos/returns").param("branchId", UUID.randomUUID().toString()))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void requiresReturnPermissions() throws Exception {
        String token = tokenWithoutReturnPermissions();
        String request = "{\"reason\":\"Prueba\",\"lines\":[{\"saleItemId\":\""
                + UUID.randomUUID() + "\",\"quantity\":1}]}";
        mockMvc.perform(post("/api/v1/pos/sales/{id}/returns", UUID.randomUUID())
                        .header("Authorization", "Bearer " + token)
                        .contentType("application/json").content(request))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/api/v1/pos/returns").param("branchId", UUID.randomUUID().toString())
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isForbidden());
    }

    private String tokenWithoutReturnPermissions() {
        Tenant tenant = tenants.save(Tenant.builder().name("Tienda " + UUID.randomUUID())
                .slug("tienda-" + UUID.randomUUID()).status(TenantStatus.active)
                .defaultCurrency("GTQ").timezone("America/Guatemala").build());
        Role role = Role.builder().name("Rol " + UUID.randomUUID()).status(RoleStatus.active)
                .permissions(List.of("pos.sales.read")).build();
        role.setTenantId(tenant.getId());
        role = roles.save(role);
        User user = User.builder().name("Empleado").email("return-" + UUID.randomUUID() + "@test.local")
                .type(UserType.employee).roleId(role.getId()).build();
        user.setTenantId(tenant.getId());
        user = users.save(user);
        Session session = sessions.save(Session.builder().userId(user.getId())
                .expiresAt(Instant.now().plus(1, ChronoUnit.HOURS).truncatedTo(ChronoUnit.SECONDS)).build());
        return jwt.generateToken(user, session);
    }
}
