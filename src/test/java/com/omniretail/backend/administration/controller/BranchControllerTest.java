package com.omniretail.backend.administration.controller;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.omniretail.backend.TestcontainersConfiguration;
import com.omniretail.backend.SubscriptionTestFixtures;
import com.omniretail.backend.administration.repository.SaasPlanRepository;
import com.omniretail.backend.administration.repository.TenantSubscriptionRepository;
import com.omniretail.backend.administration.entity.Branch;
import com.omniretail.backend.administration.entity.BranchStatus;
import com.omniretail.backend.administration.entity.BranchType;
import com.omniretail.backend.administration.entity.Role;
import com.omniretail.backend.administration.entity.Tenant;
import com.omniretail.backend.administration.entity.TenantStatus;
import com.omniretail.backend.administration.entity.User;
import com.omniretail.backend.administration.entity.UserType;
import com.omniretail.backend.administration.repository.BranchRepository;
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
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(TestcontainersConfiguration.class)
class BranchControllerTest {

    private static final String BASE_URL = "/api/v1/administration/branches";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JwtService jwtService;

    @Autowired
    private TenantRepository tenantRepository;

    @Autowired private SaasPlanRepository planRepository;
    @Autowired private TenantSubscriptionRepository subscriptionRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private RoleRepository roleRepository;

    @Autowired
    private SessionRepository sessionRepository;

    @Autowired
    private BranchRepository branchRepository;

    @Test
    void withoutTokenReturnsUnauthorized() throws Exception {
        mockMvc.perform(get(BASE_URL)).andExpect(status().isUnauthorized());
    }

    @Test
    void createBranchSuccess() throws Exception {
        Tenant tenantA = persistTenant();
        String token = tokenFor(tenantA);

        String body = """
                {"code":"centro","name":"Sucursal Centro","type":"main","address":"Zona 1, Guatemala"}
                """;

        mockMvc.perform(post(BASE_URL)
                        .header("Authorization", bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.code").value("CENTRO"))
                .andExpect(jsonPath("$.name").value("Sucursal Centro"))
                .andExpect(jsonPath("$.tenantId").value(tenantA.getId().toString()));
    }

    @Test
    void createBranchWithMaxEmailLengthSucceeds() throws Exception {
        Tenant tenant = persistTenant();
        String token = tokenFor(tenant);
        // 64 (local) + 1 (@) + 61 + 1 + 61 + 1 + 60 + 5 (.demo) = 254, el maximo de RFC 5321.
        String email254 = "b".repeat(64) + "@" + "d".repeat(61) + "." + "d".repeat(61) + "." + "d".repeat(60) + ".demo";

        String body = """
                {"code":"BR-254","name":"Sucursal Email Largo","type":"store","email":"%s"}
                """.formatted(email254);

        mockMvc.perform(post(BASE_URL)
                        .header("Authorization", bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.email").value(email254));
    }

    @Test
    void createBranchDuplicateCodeConflict() throws Exception {
        Tenant tenantA = persistTenant();
        String token = tokenFor(tenantA);

        String body = """
                {"code":"CENTRO","name":"Sucursal Centro","type":"main"}
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
                .andExpect(jsonPath("$.code").value("BRANCH_CODE_EXISTS"));
    }

    @Test
    void crossTenantIsolation() throws Exception {
        Tenant tenantA = persistTenant();
        Tenant tenantB = persistTenant();
        String tokenB = tokenFor(tenantB);

        Branch branch = Branch.builder()
                .code("CENTRO")
                .name("Sucursal Centro")
                .type(BranchType.main)
                .status(BranchStatus.active)
                .build();
        branch.setTenantId(tenantA.getId());
        branch = branchRepository.save(branch);

        mockMvc.perform(get(BASE_URL + "/" + branch.getId()).header("Authorization", bearer(tokenB)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("BRANCH_NOT_FOUND"));

        String updateBody = """
                {"name":"Sucursal Centro Actualizada","type":"main"}
                """;

        mockMvc.perform(put(BASE_URL + "/" + branch.getId())
                        .header("Authorization", bearer(tokenB))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(updateBody))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("BRANCH_NOT_FOUND"));
    }

    @Test
    void branchLimitCountsInactiveButAllowsEditingExistingBranch() throws Exception {
        Tenant tenant = persistTenant();
        String token = tokenFor(tenant);
        var limited = planRepository.saveAndFlush(com.omniretail.backend.administration.entity.SaasPlan.builder()
                .code("limited-" + UUID.randomUUID()).name("Plan limitado")
                .monthlyQuetzales(new java.math.BigDecimal("199.00"))
                .status(com.omniretail.backend.administration.entity.PlanStatus.active)
                .maxBranches(1).capabilities(List.of()).build());
        var subscription = subscriptionRepository.findByTenantIdAndStatusIn(tenant.getId(),
                List.of(com.omniretail.backend.administration.entity.TenantSubscriptionStatus.active)).orElseThrow();
        subscription.setPlanId(limited.getId());
        subscriptionRepository.saveAndFlush(subscription);
        Branch existing = Branch.builder().code("EXISTING").name("Existente")
                .type(BranchType.main).status(BranchStatus.inactive).build();
        existing.setTenantId(tenant.getId());
        existing = branchRepository.saveAndFlush(existing);

        mockMvc.perform(post(BASE_URL).header("Authorization", bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"code":"NEXT","name":"Siguiente","type":"store"}
                                """))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("LIMIT_REACHED"))
                .andExpect(jsonPath("$.message").value("Alcanzaste el límite de tu plan actual."));
        mockMvc.perform(put(BASE_URL + "/" + existing.getId()).header("Authorization", bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"Nombre actualizado","type":"main","status":"active"}
                                """))
                .andExpect(status().isOk());
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
        Tenant saved = tenantRepository.save(tenant);
        SubscriptionTestFixtures.provisionBasic(subscriptionRepository, planRepository, saved.getId());
        return saved;
    }

    private String tokenFor(Tenant tenant) {
        Role role = Role.builder()
                .name("Rol Sucursales " + UUID.randomUUID())
                .permissions(List.of("admin.branches.read", "admin.branches.manage"))
                .build();
        role.setTenantId(tenant.getId());
        role = roleRepository.save(role);

        User user = User.builder()
                .name("Empleado Demo")
                .email("empleado-" + UUID.randomUUID() + "@omniretail.local")
                .type(UserType.employee)
                .roleId(role.getId())
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
