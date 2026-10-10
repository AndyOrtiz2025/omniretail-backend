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

    @Autowired
    private com.omniretail.backend.shared.security.TenantEntitlementResolver entitlementResolver;

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
    void activeSubscriptionWinsOverMoreRecentCancelledHistoricalSubscription() throws Exception {
        Tenant tenant = tenant();
        Instant now = Instant.now();
        SaasPlan activePlan = plan(PlanStatus.active, List.of("pos", "inventory"));
        SaasPlan cancelledPlan = plan(PlanStatus.active, List.of("purchasing", "receiving"));

        subscribeAt(tenant, activePlan, TenantSubscriptionStatus.active,
                now.minus(15, ChronoUnit.DAYS), List.of());
        subscribeAt(tenant, cancelledPlan, TenantSubscriptionStatus.cancelled,
                now.minus(1, ChronoUnit.DAYS), List.of());

        entitlements(login(employee(tenant)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.planCode").value(activePlan.getCode()))
                .andExpect(jsonPath("$.subscriptionStatus").value("active"))
                .andExpect(jsonPath("$.planStatus").value("active"))
                .andExpect(jsonPath("$.isEntitlementActive").value(true))
                .andExpect(jsonPath("$.capabilities", containsInAnyOrder("pos", "inventory")))
                .andExpect(jsonPath("$.effectiveCapabilities", containsInAnyOrder("pos", "inventory")));
    }

    @Test
    void suspendedSubscriptionWinsOverMoreRecentCancelledHistoricalSubscription() throws Exception {
        Tenant tenant = tenant();
        Instant now = Instant.now();
        SaasPlan suspendedPlan = plan(PlanStatus.active, List.of("pos"));
        SaasPlan cancelledPlan = plan(PlanStatus.active, List.of("inventory", "purchasing"));

        subscribeAt(tenant, suspendedPlan, TenantSubscriptionStatus.suspended,
                now.minus(20, ChronoUnit.DAYS), List.of());
        subscribeAt(tenant, cancelledPlan, TenantSubscriptionStatus.cancelled,
                now.minus(2, ChronoUnit.DAYS), List.of());

        entitlements(login(employee(tenant)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.planCode").value(suspendedPlan.getCode()))
                .andExpect(jsonPath("$.subscriptionStatus").value("suspended"))
                .andExpect(jsonPath("$.isEntitlementActive").value(false))
                .andExpect(jsonPath("$.capabilities", containsInAnyOrder("pos")))
                .andExpect(jsonPath("$.effectiveCapabilities.length()").value(0));
    }

    @Test
    void multipleHistoricalRecordsSelectLatestCancelledWhenNoCurrentSubscriptionExists() throws Exception {
        Tenant tenant = tenant();
        Instant now = Instant.now();
        SaasPlan oldestPlan = plan(PlanStatus.active, List.of("pos"));
        SaasPlan middlePlan = plan(PlanStatus.active, List.of("inventory"));
        SaasPlan latestPlan = plan(PlanStatus.active, List.of("purchasing", "receiving"));

        subscribeAt(tenant, oldestPlan, TenantSubscriptionStatus.cancelled,
                now.minus(60, ChronoUnit.DAYS), List.of());
        subscribeAt(tenant, middlePlan, TenantSubscriptionStatus.cancelled,
                now.minus(30, ChronoUnit.DAYS), List.of());
        subscribeAt(tenant, latestPlan, TenantSubscriptionStatus.cancelled,
                now.minus(5, ChronoUnit.DAYS), List.of());

        entitlements(login(employee(tenant)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.planCode").value(latestPlan.getCode()))
                .andExpect(jsonPath("$.subscriptionStatus").value("cancelled"))
                .andExpect(jsonPath("$.isEntitlementActive").value(false))
                .andExpect(jsonPath("$.capabilities", containsInAnyOrder("purchasing", "receiving")))
                .andExpect(jsonPath("$.effectiveCapabilities.length()").value(0));
    }

    @Test
    void multipleHistoricalRecordsNeverOverrideCurrentSubscription() throws Exception {
        Tenant tenant = tenant();
        Instant now = Instant.now();
        SaasPlan currentPlan = plan(PlanStatus.active, List.of("pos", "inventory"));

        subscribeAt(tenant, plan(PlanStatus.active, List.of("purchasing")),
                TenantSubscriptionStatus.cancelled, now.minus(45, ChronoUnit.DAYS), List.of());
        subscribeAt(tenant, currentPlan,
                TenantSubscriptionStatus.active, now.minus(20, ChronoUnit.DAYS), List.of());
        subscribeAt(tenant, plan(PlanStatus.active, List.of("receiving")),
                TenantSubscriptionStatus.cancelled, now.minus(10, ChronoUnit.DAYS), List.of());
        subscribeAt(tenant, plan(PlanStatus.active, List.of("purchasing", "receiving")),
                TenantSubscriptionStatus.cancelled, now.minus(1, ChronoUnit.DAYS), List.of());

        entitlements(login(employee(tenant)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.planCode").value(currentPlan.getCode()))
                .andExpect(jsonPath("$.subscriptionStatus").value("active"))
                .andExpect(jsonPath("$.isEntitlementActive").value(true))
                .andExpect(jsonPath("$.effectiveCapabilities", containsInAnyOrder("pos", "inventory")));
    }

    @Test
    void effectiveCapabilitiesMatchBackendAuthorizationAcrossSubscriptionStates() throws Exception {
        Instant now = Instant.now();

        // 1. Active subscription WITHOUT purchasing + newer cancelled historical subscription WITH purchasing:
        //    both /auth/session/entitlements and backend authorization must deny purchasing (CAPABILITY_REQUIRED).
        Tenant activeWithHistoryTenant = tenant();
        subscribeAt(activeWithHistoryTenant, plan(PlanStatus.active, List.of("pos")),
                TenantSubscriptionStatus.active, now.minus(15, ChronoUnit.DAYS), List.of());
        subscribeAt(activeWithHistoryTenant, plan(PlanStatus.active, List.of("pos", "purchasing")),
                TenantSubscriptionStatus.cancelled, now.minus(1, ChronoUnit.DAYS), List.of());
        String tokenWithoutPurchasing = login(
                employeeWithPermissions(activeWithHistoryTenant, List.of("pos.cash.open", "purchasing.orders.read")));

        entitlements(tokenWithoutPurchasing)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.effectiveCapabilities", containsInAnyOrder("pos")));
        org.assertj.core.api.Assertions.assertThat(
                        entitlementResolver.resolve(activeWithHistoryTenant.getId()).capabilities())
                .extracting(com.omniretail.backend.shared.security.SaasCapability::getKey)
                .containsExactly("pos");
        mockMvc.perform(get("/api/v1/purchasing/suppliers/active")
                        .header("Authorization", "Bearer " + tokenWithoutPurchasing))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("CAPABILITY_REQUIRED"));

        // 2. Active subscription WITH purchasing + newer cancelled historical subscription WITHOUT purchasing:
        //    both /auth/session/entitlements and backend authorization must allow purchasing (200 OK).
        Tenant activePurchasingTenant = tenant();
        subscribeAt(activePurchasingTenant, plan(PlanStatus.active, List.of("pos", "purchasing")),
                TenantSubscriptionStatus.active, now.minus(15, ChronoUnit.DAYS), List.of());
        subscribeAt(activePurchasingTenant, plan(PlanStatus.active, List.of("pos")),
                TenantSubscriptionStatus.cancelled, now.minus(1, ChronoUnit.DAYS), List.of());
        String tokenWithPurchasing = login(
                employeeWithPermissions(activePurchasingTenant, List.of("pos.cash.open", "purchasing.orders.read")));

        entitlements(tokenWithPurchasing)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.effectiveCapabilities", containsInAnyOrder("pos", "purchasing")));
        org.assertj.core.api.Assertions.assertThat(
                        entitlementResolver.resolve(activePurchasingTenant.getId()).capabilities())
                .extracting(com.omniretail.backend.shared.security.SaasCapability::getKey)
                .containsExactlyInAnyOrder("pos", "purchasing");
        mockMvc.perform(get("/api/v1/purchasing/suppliers/active")
                        .header("Authorization", "Bearer " + tokenWithPurchasing))
                .andExpect(status().isOk());

        // 3. Suspended subscription WITH purchasing: effectiveCapabilities = [] and backend returns SUBSCRIPTION_INACTIVE.
        Tenant suspendedTenant = tenant();
        subscribe(suspendedTenant, plan(PlanStatus.active, List.of("purchasing")),
                TenantSubscriptionStatus.suspended, List.of());
        String suspendedToken = login(
                employeeWithPermissions(suspendedTenant, List.of("purchasing.orders.read")));

        entitlements(suspendedToken)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.effectiveCapabilities.length()").value(0));
        mockMvc.perform(get("/api/v1/purchasing/suppliers/active")
                        .header("Authorization", "Bearer " + suspendedToken))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("SUBSCRIPTION_INACTIVE"));

        // 4. Archived plan WITH active subscription: effectiveCapabilities = [] and backend returns PLAN_INACTIVE.
        Tenant archivedPlanTenant = tenant();
        subscribe(archivedPlanTenant, plan(PlanStatus.archived, List.of("purchasing")),
                TenantSubscriptionStatus.active, List.of());
        String archivedPlanToken = login(
                employeeWithPermissions(archivedPlanTenant, List.of("purchasing.orders.read")));

        entitlements(archivedPlanToken)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.effectiveCapabilities.length()").value(0));
        mockMvc.perform(get("/api/v1/purchasing/suppliers/active")
                        .header("Authorization", "Bearer " + archivedPlanToken))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("PLAN_INACTIVE"));

        // 5. Only cancelled historical records: effectiveCapabilities = [] and backend returns SUBSCRIPTION_INACTIVE.
        Tenant cancelledOnlyTenant = tenant();
        subscribeAt(cancelledOnlyTenant, plan(PlanStatus.active, List.of("purchasing")),
                TenantSubscriptionStatus.cancelled, now.minus(10, ChronoUnit.DAYS), List.of());
        String cancelledOnlyToken = login(
                employeeWithPermissions(cancelledOnlyTenant, List.of("purchasing.orders.read")));

        entitlements(cancelledOnlyToken)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.subscriptionStatus").value("cancelled"))
                .andExpect(jsonPath("$.effectiveCapabilities.length()").value(0));
        mockMvc.perform(get("/api/v1/purchasing/suppliers/active")
                        .header("Authorization", "Bearer " + cancelledOnlyToken))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("SUBSCRIPTION_INACTIVE"));
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
        subscribeAt(tenant, plan, status, Instant.now(), addons);
    }

    private void subscribeAt(
            Tenant tenant, SaasPlan plan, TenantSubscriptionStatus status, Instant startedAt, List<String> addons) {
        TenantSubscription subscription = TenantSubscription.builder()
                .planId(plan.getId())
                .status(status)
                .startedAt(startedAt)
                .currentPeriodStart(startedAt)
                .currentPeriodEnd(startedAt.plus(30, ChronoUnit.DAYS))
                .addonCodes(addons)
                .build();
        subscription.setTenantId(tenant.getId());
        subscriptionRepository.saveAndFlush(subscription);
    }

    /** Empleado sin ningun permiso administrativo: justo el caso de Cajero/Inventario/Bodeguero. */
    private User employee(Tenant tenant) {
        return employeeWithPermissions(tenant, List.of("pos.cash.open"));
    }

    private User employeeWithPermissions(Tenant tenant, List<String> permissions) {
        Role role = Role.builder()
                .name("Rol " + UUID.randomUUID())
                .status(RoleStatus.active)
                .branchScope(BranchScope.all)
                .permissions(permissions)
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
