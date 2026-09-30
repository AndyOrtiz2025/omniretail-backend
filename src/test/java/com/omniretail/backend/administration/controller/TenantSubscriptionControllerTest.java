package com.omniretail.backend.administration.controller;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.omniretail.backend.TestcontainersConfiguration;
import com.omniretail.backend.administration.entity.Role;
import com.omniretail.backend.administration.entity.SaasPlan;
import com.omniretail.backend.administration.entity.SaasPlanCurrency;
import com.omniretail.backend.administration.entity.Tenant;
import com.omniretail.backend.administration.entity.TenantStatus;
import com.omniretail.backend.administration.entity.User;
import com.omniretail.backend.administration.entity.UserType;
import com.omniretail.backend.administration.repository.RoleRepository;
import com.omniretail.backend.administration.repository.SaasPlanRepository;
import com.omniretail.backend.administration.repository.TenantRepository;
import com.omniretail.backend.administration.repository.UserRepository;
import com.omniretail.backend.auth.entity.Session;
import com.omniretail.backend.auth.repository.SessionRepository;
import com.omniretail.backend.auth.service.JwtService;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
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
class TenantSubscriptionControllerTest {

    private static final String BASE_URL = "/api/v1/admin/subscriptions";
    private static final String PERMISSION = "administration.subscriptions.manage";

    @Autowired private MockMvc mockMvc;
    @Autowired private TenantRepository tenantRepository;
    @Autowired private RoleRepository roleRepository;
    @Autowired private UserRepository userRepository;
    @Autowired private SessionRepository sessionRepository;
    @Autowired private SaasPlanRepository planRepository;
    @Autowired private JwtService jwtService;

    @Test
    void withoutTokenReturnsUnauthorized() throws Exception {
        mockMvc.perform(get(BASE_URL + "/current")).andExpect(status().isUnauthorized());
    }

    @Test
    void withoutPermissionReturnsForbidden() throws Exception {
        Tenant tenant = persistTenant();
        mockMvc.perform(get(BASE_URL + "/current")
                        .header("Authorization", bearer(tokenFor(tenant, List.of()))))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("ACCESS_DENIED"));
    }

    @Test
    void nullPlanIdReturnsBadRequest() throws Exception {
        Tenant tenant = persistTenant();
        mockMvc.perform(post(BASE_URL)
                        .header("Authorization", bearer(tokenFor(tenant, List.of(PERMISSION))))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"planId\":null}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void assignReturnsContractAndCurrentSubscriptionIsTenantIsolated() throws Exception {
        SaasPlan plan = persistPlan();
        Tenant tenantA = persistTenant();
        Tenant tenantB = persistTenant();
        String tokenA = tokenFor(tenantA, List.of(PERMISSION));
        String tokenB = tokenFor(tenantB, List.of(PERMISSION));

        mockMvc.perform(post(BASE_URL)
                        .header("Authorization", bearer(tokenA))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"planId\":\"%s\"}".formatted(plan.getId())))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.tenantId").value(tenantA.getId().toString()))
                .andExpect(jsonPath("$.status").value("active"))
                .andExpect(jsonPath("$.cancelAtPeriodEnd").value(false))
                .andExpect(jsonPath("$.currentPeriodStart").isNotEmpty())
                .andExpect(jsonPath("$.currentPeriodEnd").isNotEmpty())
                .andExpect(jsonPath("$.plan.id").value(plan.getId().toString()))
                .andExpect(jsonPath("$.plan.code").value(plan.getCode()))
                .andExpect(jsonPath("$.plan.capabilities[0]").value("pos"));

        mockMvc.perform(get(BASE_URL + "/current").header("Authorization", bearer(tokenA)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.tenantId").value(tenantA.getId().toString()));

        mockMvc.perform(get(BASE_URL + "/current").header("Authorization", bearer(tokenB)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("TENANT_SUBSCRIPTION_NOT_FOUND"));
    }

    private SaasPlan persistPlan() {
        String suffix = UUID.randomUUID().toString();
        return planRepository.save(SaasPlan.builder()
                .code("test-" + suffix)
                .name("Plan " + suffix)
                .maxBranches(5)
                .maxUsers(10)
                .maxProducts(100)
                .priceMonthly(new BigDecimal("29.00"))
                .currency(SaasPlanCurrency.USD)
                .capabilities(List.of("pos"))
                .active(true)
                .build());
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
        Role role = Role.builder()
                .name("Subscription actor " + UUID.randomUUID())
                .permissions(permissions)
                .build();
        role.setTenantId(tenant.getId());
        role = roleRepository.save(role);
        User user = User.builder()
                .name("Subscription actor")
                .email("subscription-" + UUID.randomUUID() + "@test.local")
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
