package com.omniretail.backend.administration.controller;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import com.omniretail.backend.TestcontainersConfiguration;
import com.omniretail.backend.administration.entity.*;
import com.omniretail.backend.administration.repository.*;
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
 private static final String READ = "admin.plans.read";
 private static final String MANAGE = "admin.plans.manage";
 @Autowired private MockMvc mockMvc;
 @Autowired private TenantRepository tenantRepository;
 @Autowired private RoleRepository roleRepository;
 @Autowired private UserRepository userRepository;
 @Autowired private SessionRepository sessionRepository;
 @Autowired private SaasPlanRepository planRepository;
 @Autowired private TenantSubscriptionRepository subscriptionRepository;
 @Autowired private JwtService jwtService;

 @Test void withoutTokenReturnsUnauthorized() throws Exception {
  mockMvc.perform(get(BASE_URL)).andExpect(status().isUnauthorized());
 }
 @Test void withoutPermissionReturnsForbidden() throws Exception {
  Tenant tenant = persistTenant();
  mockMvc.perform(get(BASE_URL).header("Authorization", bearer(tokenFor(tenant, List.of()))))
    .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("ACCESS_DENIED"));
 }
 @Test void readOnlyActorReadsSnapshotButCannotChangeAddons() throws Exception {
  Tenant tenant = persistTenant();
  persistSubscription(tenant);
  String token = tokenFor(tenant, List.of(READ));
  mockMvc.perform(get(BASE_URL).header("Authorization", bearer(token)))
    .andExpect(status().isOk())
    .andExpect(jsonPath("$.tenantId").value(tenant.getId().toString()))
    .andExpect(jsonPath("$.subscription.status").value("active"))
    .andExpect(jsonPath("$.plan.code").value("basic"))
    .andExpect(jsonPath("$.usage[0].key").value("maxEmployees"))
    .andExpect(jsonPath("$.invoices[0].status").value("simulated"))
    .andExpect(jsonPath("$.invoices[0].totalQuetzales").value(199.0));
  mockMvc.perform(put(BASE_URL + "/addons").header("Authorization", bearer(token))
    .contentType(MediaType.APPLICATION_JSON).content("{\"addonCodes\":[\"advanced_reports\"]}"))
    .andExpect(status().isForbidden());
 }
 @Test void addonUpdatesAreTenantIsolatedAndSnapshotPriceIsImmutable() throws Exception {
  Tenant tenantA = persistTenant();
  Tenant tenantB = persistTenant();
  persistSubscription(tenantA);
  persistSubscription(tenantB);
  String tokenA = tokenFor(tenantA, List.of(READ, MANAGE));
  String tokenB = tokenFor(tenantB, List.of(READ));
  mockMvc.perform(put(BASE_URL + "/addons").header("Authorization", bearer(tokenA))
    .contentType(MediaType.APPLICATION_JSON).content("{\"addonCodes\":[\"ecommerce_delivery\",\"advanced_reports\"]}"))
    .andExpect(status().isOk())
    .andExpect(jsonPath("$.addonCodes[0]").value("advanced_reports"))
    .andExpect(jsonPath("$.invoices[0].totalQuetzales").value(199.0))
    .andExpect(jsonPath("$.invoices[0].addonCodes").isEmpty());
  mockMvc.perform(get(BASE_URL).header("Authorization", bearer(tokenA)))
    .andExpect(status().isOk()).andExpect(jsonPath("$.invoices.length()").value(1));
  mockMvc.perform(get(BASE_URL).header("Authorization", bearer(tokenB)))
    .andExpect(status().isOk()).andExpect(jsonPath("$.tenantId").value(tenantB.getId().toString()))
    .andExpect(jsonPath("$.addonCodes").isEmpty());
 }
 @Test void invalidAddonsReturnBadRequest() throws Exception {
  Tenant tenant = persistTenant();
  String token = tokenFor(tenant, List.of(MANAGE));
  for (String body : List.of("{\"addonCodes\":null}", "{\"addonCodes\":[\"unknown\"]}",
     "{\"addonCodes\":[\"advanced_reports\",\"advanced_reports\"]}", "{\"addonCodes\":[\"\"]}")) {
   mockMvc.perform(put(BASE_URL + "/addons").header("Authorization", bearer(token))
     .contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isBadRequest());
  }
 }
 @Test void removedEndpointsCannotMutateSubscription() throws Exception {
  Tenant tenant = persistTenant();
  String token = tokenFor(tenant, List.of(READ, MANAGE));
  mockMvc.perform(post(BASE_URL).header("Authorization", bearer(token)).contentType(MediaType.APPLICATION_JSON)
    .content("{\"planId\":\"00000000-0000-0000-0000-000000000199\"}")).andExpect(status().isMethodNotAllowed());
  mockMvc.perform(put(BASE_URL + "/renew").header("Authorization", bearer(token))).andExpect(status().isNotFound());
  mockMvc.perform(put(BASE_URL + "/cancel-at-period-end").header("Authorization", bearer(token))).andExpect(status().isNotFound());
 }
 private TenantSubscription persistSubscription(Tenant tenant) {
  Instant now = Instant.now();
  TenantSubscription subscription = TenantSubscription.builder()
    .planId(UUID.fromString("00000000-0000-0000-0000-000000000199"))
    .status(TenantSubscriptionStatus.active).startedAt(now).currentPeriodStart(now)
    .currentPeriodEnd(now.plus(31, ChronoUnit.DAYS)).addonCodes(List.of()).build();
  subscription.setTenantId(tenant.getId());
  return subscriptionRepository.save(subscription);
 }

    private Tenant persistTenant() {
        String suffix = UUID.randomUUID().toString();
        return tenantRepository.save(Tenant.builder()
                .name("Tenant " + suffix)
                .slug("tenant-" + suffix)
                .status(TenantStatus.active)
                .defaultCurrency("GTQ")
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
