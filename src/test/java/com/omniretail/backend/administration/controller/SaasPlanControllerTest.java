package com.omniretail.backend.administration.controller;

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
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest(properties = "app.saas-administration.platform-tenant-id=aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa")
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(TestcontainersConfiguration.class)
class SaasPlanControllerTest {

    private static final String BASE_URL = "/api/v1/admin/plans";
    private static final String PERMISSION_READ = "admin.plans.read";
    private static final String PERMISSION_MANAGE = "admin.plans.manage";
    private static final UUID PLATFORM_TENANT_ID = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");

    @Autowired private MockMvc mockMvc;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private TenantRepository tenantRepository;
    @Autowired private RoleRepository roleRepository;
    @Autowired private UserRepository userRepository;
    @Autowired private SessionRepository sessionRepository;
    @Autowired private JwtService jwtService;

    @BeforeEach
    void ensurePlatformTenant() {
        jdbc.update("""
                INSERT INTO tenants (id, name, slug, status, default_currency, timezone)
                VALUES (?, 'Platform', 'platform-test', 'active', 'USD', 'UTC')
                ON CONFLICT (id) DO NOTHING
                """, PLATFORM_TENANT_ID);
    }

    @Test
    void withoutTokenReturnsUnauthorized() throws Exception {
        mockMvc.perform(get(BASE_URL)).andExpect(status().isUnauthorized());
    }

    @Test
    void withoutPermissionReturnsForbidden() throws Exception {
        Tenant tenant = tenantRepository.findById(PLATFORM_TENANT_ID).orElseThrow();
        mockMvc.perform(get(BASE_URL).header("Authorization", bearer(tokenFor(tenant, List.of()))))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("ACCESS_DENIED"));
    }

    @Test
    void readPermissionCanListPlansButCannotCreateOne() throws Exception {
        Tenant tenant = tenantRepository.findById(PLATFORM_TENANT_ID).orElseThrow();
        String token = tokenFor(tenant, List.of(PERMISSION_READ));

        mockMvc.perform(get(BASE_URL).header("Authorization", bearer(token)))
                .andExpect(status().isOk());

        mockMvc.perform(post(BASE_URL)
                        .header("Authorization", bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"code":"read-only-%s","name":"Read only","maxBranches":1,"maxUsers":1,
                                 "maxProducts":1,"priceMonthly":1,"currency":"USD","capabilities":["pos"]}
                                """.formatted(UUID.randomUUID())))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("ACCESS_DENIED"));
    }

    @Test
    void invalidCreateRequestReturnsBadRequest() throws Exception {
        Tenant tenant = tenantRepository.findById(PLATFORM_TENANT_ID).orElseThrow();
        mockMvc.perform(post(BASE_URL)
                        .header("Authorization", bearer(tokenFor(tenant, List.of(PERMISSION_MANAGE))))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"code":"INVALID CODE","name":"","maxBranches":0,"maxUsers":0,
                                 "maxProducts":0,"priceMonthly":-1,"currency":"USD","capabilities":[]}
                                """))
                .andExpect(status().isBadRequest());
    }

    @Test
    void platformTenantCanCreatePlanWithExpectedContract() throws Exception {
        Tenant tenant = tenantRepository.findById(PLATFORM_TENANT_ID).orElseThrow();
        String code = "plan-" + UUID.randomUUID();
        mockMvc.perform(post(BASE_URL)
                        .header("Authorization", bearer(tokenFor(tenant, List.of(PERMISSION_MANAGE))))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"code":"%s","name":"Growth","description":"Plan growth",
                                 "maxBranches":3,"maxUsers":10,"maxProducts":1000,
                                 "priceMonthly":49.90,"currency":"USD",
                                 "capabilities":["pos","inventory"]}
                                """.formatted(code)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").isNotEmpty())
                .andExpect(jsonPath("$.code").value(code))
                .andExpect(jsonPath("$.name").value("Growth"))
                .andExpect(jsonPath("$.maxBranches").value(3))
                .andExpect(jsonPath("$.priceMonthly").value(49.90))
                .andExpect(jsonPath("$.currency").value("USD"))
                .andExpect(jsonPath("$.capabilities.length()").value(2))
                .andExpect(jsonPath("$.active").value(true));
    }

    @Test
    void tenantWithPermissionCannotMutateGlobalPlanCatalog() throws Exception {
        Tenant ordinaryTenant = persistTenant();
        String code = "forbidden-" + UUID.randomUUID();

        mockMvc.perform(post(BASE_URL)
                        .header("Authorization", bearer(tokenFor(ordinaryTenant, List.of(PERMISSION_MANAGE))))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"code":"%s","name":"Forbidden","maxBranches":1,"maxUsers":1,
                                 "maxProducts":1,"priceMonthly":1,"currency":"USD","capabilities":["pos"]}
                                """.formatted(code)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("PLATFORM_TENANT_REQUIRED"));
    }

    private Tenant persistTenant() {
        String suffix = UUID.randomUUID().toString();
        return tenantRepository.save(Tenant.builder()
                .name("Tenant " + suffix)
                .slug("tenant-" + suffix)
                .status(TenantStatus.active)
                .defaultCurrency("USD")
                .timezone("UTC")
                .build());
    }

    private String tokenFor(Tenant tenant, List<String> permissions) {
        Role role = Role.builder().name("Plan actor " + UUID.randomUUID()).permissions(permissions).build();
        role.setTenantId(tenant.getId());
        role = roleRepository.save(role);
        User user = User.builder()
                .name("Plan actor")
                .email("plan-" + UUID.randomUUID() + "@test.local")
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
