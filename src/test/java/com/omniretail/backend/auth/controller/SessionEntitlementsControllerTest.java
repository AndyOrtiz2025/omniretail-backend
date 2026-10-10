package com.omniretail.backend.auth.controller;

import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.hasKey;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import com.omniretail.backend.TestcontainersConfiguration;
import com.omniretail.backend.administration.entity.BranchScope;
import com.omniretail.backend.administration.entity.PlanStatus;
import com.omniretail.backend.administration.entity.Role;
import com.omniretail.backend.administration.entity.RoleStatus;
import com.omniretail.backend.administration.entity.SaasPlan;
import com.omniretail.backend.administration.entity.Tenant;
import com.omniretail.backend.administration.entity.TenantStatus;
import com.omniretail.backend.administration.entity.TenantSubscription;
import com.omniretail.backend.administration.entity.TenantSubscriptionStatus;
import com.omniretail.backend.administration.entity.User;
import com.omniretail.backend.administration.entity.UserType;
import com.omniretail.backend.administration.repository.RoleRepository;
import com.omniretail.backend.administration.repository.SaasPlanRepository;
import com.omniretail.backend.administration.repository.TenantRepository;
import com.omniretail.backend.administration.repository.TenantSubscriptionRepository;
import com.omniretail.backend.administration.repository.UserRepository;
import com.omniretail.backend.auth.entity.AccountStatus;
import com.omniretail.backend.auth.entity.AuthAccount;
import com.omniretail.backend.auth.repository.AuthAccountRepository;
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
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(TestcontainersConfiguration.class)
class SessionEntitlementsControllerTest {

    private static final String LOGIN = "/api/v1/auth/login";
    private static final String ENTITLEMENTS = "/api/v1/auth/session/entitlements";
    private static final String PASSWORD = "Empleado1234!";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private TenantRepository tenantRepository;

    @Autowired
    private RoleRepository roleRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private AuthAccountRepository authAccountRepository;

    @Autowired
    private SaasPlanRepository planRepository;

    @Autowired
    private TenantSubscriptionRepository subscriptionRepository;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Test
    void employeeWithoutPlanPermissionReadsPlanCapabilities() throws Exception {
        Tenant tenant = tenant();
        subscribe(tenant, plan(PlanStatus.active, List.of("pos", "inventory")),
                TenantSubscriptionStatus.active, List.of());

        entitlements(login(employee(tenant)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.tenantId").value(tenant.getId().toString()))
                .andExpect(jsonPath("$.subscriptionStatus").value("active"))
                .andExpect(jsonPath("$.planStatus").value("active"))
                .andExpect(jsonPath("$.isEntitlementActive").value(true))
                .andExpect(jsonPath("$.capabilities", containsInAnyOrder("pos", "inventory")))
                .andExpect(jsonPath("$.effectiveCapabilities", containsInAnyOrder("pos", "inventory")));
    }

    @Test
    void addonsAddTheirCapabilities() throws Exception {
        Tenant tenant = tenant();
        subscribe(tenant, plan(PlanStatus.active, List.of("pos")),
                TenantSubscriptionStatus.active, List.of("ecommerce_delivery"));

        entitlements(login(employee(tenant)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.capabilities", containsInAnyOrder("pos", "ecommerce", "delivery")))
                .andExpect(jsonPath("$.effectiveCapabilities", containsInAnyOrder("pos", "ecommerce", "delivery")));
    }

    @Test
    void inactiveSubscriptionKeepsCapabilitiesButHasNoEffectiveOnes() throws Exception {
        Tenant tenant = tenant();
        subscribe(tenant, plan(PlanStatus.active, List.of("pos")),
                TenantSubscriptionStatus.suspended, List.of());

        entitlements(login(employee(tenant)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.subscriptionStatus").value("suspended"))
                .andExpect(jsonPath("$.isEntitlementActive").value(false))
                .andExpect(jsonPath("$.capabilities", containsInAnyOrder("pos")))
                .andExpect(jsonPath("$.effectiveCapabilities.length()").value(0));
    }

    @Test
    void archivedPlanHasNoEffectiveCapabilities() throws Exception {
        Tenant tenant = tenant();
        subscribe(tenant, plan(PlanStatus.archived, List.of("pos")),
                TenantSubscriptionStatus.active, List.of());

        entitlements(login(employee(tenant)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.planStatus").value("archived"))
                .andExpect(jsonPath("$.isEntitlementActive").value(false))
                .andExpect(jsonPath("$.effectiveCapabilities.length()").value(0));
    }

    @Test
    void limitsOmitUnboundedValues() throws Exception {
        Tenant tenant = tenant();
        SaasPlan plan = SaasPlan.builder()
                .code("lim-" + UUID.randomUUID()).name("Plan limitado")
                .monthlyQuetzales(new BigDecimal("199.00")).status(PlanStatus.active)
                .maxBranches(3).capabilities(List.of("pos")).build();
        subscribe(tenant, planRepository.saveAndFlush(plan), TenantSubscriptionStatus.active, List.of());

        entitlements(login(employee(tenant)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.limits.maxBranches").value(3))
                .andExpect(jsonPath("$.limits", not(hasKey("maxEmployees"))));
    }

    @Test
    void responseDoesNotExposeBillingOrOtherPlans() throws Exception {
        Tenant tenant = tenant();
        subscribe(tenant, plan(PlanStatus.active, List.of("pos")), TenantSubscriptionStatus.active, List.of());

        entitlements(login(employee(tenant)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", not(hasKey("monthlyQuetzales"))))
                .andExpect(jsonPath("$", not(hasKey("invoices"))))
                .andExpect(jsonPath("$", not(hasKey("availablePlans"))))
                .andExpect(jsonPath("$", not(hasKey("addonCodes"))));
    }

    @Test
    void tenantWithoutSubscriptionIsNotFound() throws Exception {
        Tenant tenant = tenant();

        entitlements(login(employee(tenant)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("TENANT_SUBSCRIPTION_NOT_FOUND"));
    }

    @Test
    void ignoresSubscriptionsFromOtherTenants() throws Exception {
        Tenant tenant = tenant();
        subscribe(tenant(), plan(PlanStatus.active, List.of("pos")), TenantSubscriptionStatus.active, List.of());

        entitlements(login(employee(tenant)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("TENANT_SUBSCRIPTION_NOT_FOUND"));
    }

    @Test
    void customersAreForbidden() throws Exception {
        Tenant tenant = tenant();
        subscribe(tenant, plan(PlanStatus.active, List.of("pos")), TenantSubscriptionStatus.active, List.of());
        User customer = user(tenant, UserType.customer, null);

        entitlements(login(customer))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("ENTITLEMENTS_NOT_ALLOWED"));
    }

    @Test
    void requiresAToken() throws Exception {
        mockMvc.perform(get(ENTITLEMENTS)).andExpect(status().isUnauthorized());
    }

    private ResultActions entitlements(String token) throws Exception {
        return mockMvc.perform(get(ENTITLEMENTS).header("Authorization", "Bearer " + token));
    }

    private String login(User user) throws Exception {
        String body = mockMvc.perform(post(LOGIN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\": \"%s\", \"password\": \"%s\", \"tenantSlug\": \"%s\"}"
                                .formatted(user.getEmail(), PASSWORD, tenantSlug(user))))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return JsonPath.read(body, "$.token");
    }

    private String tenantSlug(User user) {
        return tenantRepository.findById(user.getTenantId()).orElseThrow().getSlug();
    }

    private Tenant tenant() {
        return tenantRepository.save(Tenant.builder()
                .name("Tienda test")
                .slug("tienda-" + UUID.randomUUID())
                .status(TenantStatus.active)
                .defaultCurrency("GTQ")
                .timezone("America/Guatemala")
                .build());
    }

    private SaasPlan plan(PlanStatus status, List<String> capabilities) {
        return planRepository.saveAndFlush(SaasPlan.builder()
                .code("plan-" + UUID.randomUUID()).name("Plan test")
                .monthlyQuetzales(new BigDecimal("99.00")).status(status)
                .capabilities(capabilities).build());
    }

    private void subscribe(Tenant tenant, SaasPlan plan, TenantSubscriptionStatus status, List<String> addons) {
        Instant now = Instant.now();
        TenantSubscription subscription = TenantSubscription.builder()
                .planId(plan.getId())
                .status(status)
                .startedAt(now)
                .currentPeriodStart(now)
                .currentPeriodEnd(now.plus(30, ChronoUnit.DAYS))
                .addonCodes(addons)
                .build();
        subscription.setTenantId(tenant.getId());
        subscriptionRepository.saveAndFlush(subscription);
    }

    /** Empleado sin ningun permiso administrativo: justo el caso de Cajero/Inventario/Bodeguero. */
    private User employee(Tenant tenant) {
        Role role = Role.builder()
                .name("Rol " + UUID.randomUUID())
                .status(RoleStatus.active)
                .branchScope(BranchScope.all)
                .permissions(List.of("pos.cash.open"))
                .build();
        role.setTenantId(tenant.getId());
        role = roleRepository.save(role);
        return user(tenant, UserType.employee, role.getId());
    }

    private User user(Tenant tenant, UserType type, UUID roleId) {
        String email = "usuario-" + UUID.randomUUID() + "@test.local";
        User user = User.builder()
                .name("Usuario test")
                .email(email)
                .type(type)
                .roleId(roleId)
                .allowedBranchIds(List.of())
                .build();
        user.setTenantId(tenant.getId());
        user = userRepository.save(user);
        authAccountRepository.save(AuthAccount.builder()
                .userId(user.getId())
                .email(email)
                .passwordHash(passwordEncoder.encode(PASSWORD))
                .status(AccountStatus.active)
                .build());
        return user;
    }
}
