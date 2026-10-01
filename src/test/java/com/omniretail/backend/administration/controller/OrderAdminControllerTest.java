package com.omniretail.backend.administration.controller;

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

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(TestcontainersConfiguration.class)
class OrderAdminControllerTest {

    private static final String BASE_URL = "/api/v1/administration/orders";

    @Autowired private MockMvc mockMvc;
    @Autowired private JwtService jwtService;
    @Autowired private TenantRepository tenantRepository;
    @Autowired private RoleRepository roleRepository;
    @Autowired private UserRepository userRepository;
    @Autowired private SessionRepository sessionRepository;
    @Autowired private JdbcTemplate jdbc;

    @Test
    void listIsPaginatedAndIsolatedToTheAuthenticatedTenant() throws Exception {
        Tenant tenantA = tenant();
        Tenant tenantB = tenant();
        UUID branchA = branch(tenantA);
        UUID branchB = branch(tenantB);
        order(tenantA.getId(), branchA, "WEB-A-1");
        order(tenantA.getId(), branchA, "WEB-A-2");
        order(tenantB.getId(), branchB, "WEB-B-1");

        mockMvc.perform(get(BASE_URL).param("page", "0").param("size", "1")
                        .header("Authorization", bearer(tokenFor(tenantA, List.of("admin.orders.read")))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.page").value(1))
                .andExpect(jsonPath("$.pageSize").value(1))
                .andExpect(jsonPath("$.totalItems").value(2))
                .andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.items[0].orderNumber").value(org.hamcrest.Matchers.startsWith("WEB-A-")));
    }

    @Test
    void orderFromAnotherTenantReturnsNotFound() throws Exception {
        Tenant tenantA = tenant();
        Tenant tenantB = tenant();
        UUID orderOfB = order(tenantB.getId(), branch(tenantB), "WEB-B-HIDDEN");

        mockMvc.perform(get(BASE_URL + "/" + orderOfB)
                        .header("Authorization", bearer(tokenFor(tenantA, List.of("admin.orders.read")))))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("ORDER_NOT_FOUND"));
    }

    private Tenant tenant() {
        String suffix = UUID.randomUUID().toString();
        return tenantRepository.save(Tenant.builder()
                .name("Tenant " + suffix)
                .slug("tenant-" + suffix)
                .status(TenantStatus.active)
                .defaultCurrency("GTQ")
                .timezone("America/Guatemala")
                .build());
    }

    private UUID branch(Tenant tenant) {
        UUID id = UUID.randomUUID();
        String suffix = UUID.randomUUID().toString();
        jdbc.update("""
                INSERT INTO branches (id, tenant_id, code, name, type, status)
                VALUES (?, ?, ?, ?, 'main', 'active')
                """, id, tenant.getId(), "BR-" + suffix.substring(0, 8), "Sucursal " + suffix);
        return id;
    }

    private UUID order(UUID tenantId, UUID branchId, String orderNumber) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO orders (id, tenant_id, branch_id, order_number, source, guest_customer, status,
                    delivery_method, transport_mode, subtotal, discount_total, shipping_total, total, tracking_token)
                VALUES (?, ?, ?, ?, 'ecommerce', '{}'::jsonb, 'confirmed', 'store_pickup', 'none',
                    10.00, 0.00, 0.00, 10.00, ?)
                """, id, tenantId, branchId, orderNumber, UUID.randomUUID().toString());
        return id;
    }

    private String tokenFor(Tenant tenant, List<String> permissions) {
        Role role = Role.builder().name("Rol " + UUID.randomUUID()).permissions(permissions).build();
        role.setTenantId(tenant.getId());
        role = roleRepository.save(role);
        User user = User.builder()
                .name("Empleado")
                .email("employee-" + UUID.randomUUID() + "@omniretail.local")
                .type(UserType.employee)
                .roleId(role.getId())
                .build();
        user.setTenantId(tenant.getId());
        user = userRepository.save(user);
        Session session = sessionRepository.save(Session.builder()
                .userId(user.getId())
                .expiresAt(Instant.now().plus(1, ChronoUnit.HOURS))
                .build());
        return jwtService.generateToken(user, session);
    }

    private static String bearer(String token) {
        return "Bearer " + token;
    }
}
