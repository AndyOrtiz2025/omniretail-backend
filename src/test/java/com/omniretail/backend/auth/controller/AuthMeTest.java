package com.omniretail.backend.auth.controller;

import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.containsInAnyOrder;
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
import com.omniretail.backend.administration.entity.UserStatus;
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
import org.springframework.test.web.servlet.ResultActions;

/** En perfil test no se carga la semilla: cada test crea su tenant, rol, usuario y una sesion real. */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(TestcontainersConfiguration.class)
class AuthMeTest {

    private static final String ME = "/api/v1/auth/me";
    private static final String LOGOUT = "/api/v1/auth/logout";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private TenantRepository tenantRepository;

    @Autowired
    private RoleRepository roleRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private SessionRepository sessionRepository;

    @Autowired
    private JwtService jwtService;

    @Test
    void employeeWithActiveRoleGetsFullSnapshot() throws Exception {
        Tenant tenant = tenant(TenantStatus.active);
        Role role = role(tenant, RoleStatus.active, "admin.branches.read", "admin.branches.manage");
        UUID allowedBranch = UUID.randomUUID();
        User user = User.builder()
                .name("Empleado completo")
                .email("user-" + UUID.randomUUID() + "@test.local")
                .phone("5555-1234")
                .employeeCode("EMP-" + UUID.randomUUID().toString().substring(0, 8))
                .type(UserType.employee)
                .roleId(role.getId())
                .allowedBranchIds(List.of(allowedBranch))
                .build();
        user.setTenantId(tenant.getId());
        user = userRepository.save(user);
        Session session = session(user, true);

        me(jwtService.generateToken(user, session))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.user.id").value(user.getId().toString()))
                .andExpect(jsonPath("$.user.email").value(user.getEmail()))
                .andExpect(jsonPath("$.user.phone").value("5555-1234"))
                .andExpect(jsonPath("$.user.employeeCode").value(user.getEmployeeCode()))
                .andExpect(jsonPath("$.user.customerId").doesNotExist())
                .andExpect(jsonPath("$.user.allowedBranchIds", contains(allowedBranch.toString())))
                .andExpect(jsonPath("$.user.type").value("employee"))
                .andExpect(jsonPath("$.user.status").value("active"))
                .andExpect(jsonPath("$.user.roleId").value(role.getId().toString()))
                .andExpect(jsonPath("$.user.passwordHash").doesNotExist())
                .andExpect(jsonPath("$.role.id").value(role.getId().toString()))
                .andExpect(jsonPath("$.role.permissions",
                        containsInAnyOrder("admin.branches.read", "admin.branches.manage")))
                .andExpect(jsonPath("$.role.branchScope").value("assigned"))
                .andExpect(jsonPath("$.role.status").value("active"))
                .andExpect(jsonPath("$.tenant.id").value(tenant.getId().toString()))
                .andExpect(jsonPath("$.tenant.slug").value(tenant.getSlug()))
                .andExpect(jsonPath("$.session.id").value(session.getId().toString()))
                .andExpect(jsonPath("$.session.rememberMe").value(true))
                .andExpect(jsonPath("$.session.expiresAt").exists());
    }

    @Test
    void customerWithoutRoleGetsNullRole() throws Exception {
        Tenant tenant = tenant(TenantStatus.active);
        User customer = user(tenant, UserType.customer, UserStatus.active, null);

        me(tokenFor(customer))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.user.type").value("customer"))
                .andExpect(jsonPath("$.role").doesNotExist())
                .andExpect(jsonPath("$.tenant.id").value(tenant.getId().toString()));
    }

    @Test
    void customerWithArchivedRoleGetsNullRole() throws Exception {
        Tenant tenant = tenant(TenantStatus.active);
        User customer = user(tenant, UserType.customer, UserStatus.active, role(tenant, RoleStatus.archived, "x.y"));

        me(tokenFor(customer))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.role").doesNotExist());
    }

    @Test
    void nullAllowedBranchIdsAreReturnedAsEmptyList() throws Exception {
        Tenant tenant = tenant(TenantStatus.active);
        User customer = user(tenant, UserType.customer, UserStatus.active, null);

        me(tokenFor(customer))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.user.allowedBranchIds").isArray())
                .andExpect(jsonPath("$.user.allowedBranchIds").isEmpty());
    }

    @Test
    void employeeWithoutRoleIsUnauthorized() throws Exception {
        Tenant tenant = tenant(TenantStatus.active);
        User user = user(tenant, UserType.employee, UserStatus.active, null);

        me(tokenFor(user))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));
    }

    @Test
    void employeeWithArchivedRoleIsUnauthorized() throws Exception {
        Tenant tenant = tenant(TenantStatus.active);
        User user = user(tenant, UserType.employee, UserStatus.active, role(tenant, RoleStatus.archived, "x.y"));

        me(tokenFor(user)).andExpect(status().isUnauthorized());
    }

    @Test
    void employeeWithRoleFromAnotherTenantIsUnauthorized() throws Exception {
        Tenant tenant = tenant(TenantStatus.active);
        Role foreignRole = role(tenant(TenantStatus.active), RoleStatus.active, "x.y");
        User user = user(tenant, UserType.employee, UserStatus.active, foreignRole);

        me(tokenFor(user)).andExpect(status().isUnauthorized());
    }

    @Test
    void inactiveUserIsUnauthorized() throws Exception {
        Tenant tenant = tenant(TenantStatus.active);
        User user = user(tenant, UserType.employee, UserStatus.inactive, role(tenant, RoleStatus.active, "x.y"));

        me(tokenFor(user))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));
    }

    @Test
    void employeeOfInactiveTenantIsUnauthorized() throws Exception {
        Tenant tenant = tenant(TenantStatus.inactive);
        User user = user(tenant, UserType.employee, UserStatus.active, role(tenant, RoleStatus.active, "x.y"));

        me(tokenFor(user)).andExpect(status().isUnauthorized());
    }

    @Test
    void withoutTokenIsUnauthorized() throws Exception {
        mockMvc.perform(get(ME)).andExpect(status().isUnauthorized());
    }

    @Test
    void afterLogoutIsUnauthorized() throws Exception {
        Tenant tenant = tenant(TenantStatus.active);
        String token = tokenFor(user(tenant, UserType.employee, UserStatus.active, role(tenant, RoleStatus.active, "x.y")));

        me(token).andExpect(status().isOk());
        mockMvc.perform(post(LOGOUT).header("Authorization", "Bearer " + token)).andExpect(status().isNoContent());
        me(token).andExpect(status().isUnauthorized());
    }

    private ResultActions me(String token) throws Exception {
        return mockMvc.perform(get(ME).header("Authorization", "Bearer " + token));
    }

    private Tenant tenant(TenantStatus status) {
        return tenantRepository.save(Tenant.builder()
                .name("Tienda test")
                .slug("tienda-" + UUID.randomUUID())
                .status(status)
                .defaultCurrency("GTQ")
                .timezone("America/Guatemala")
                .build());
    }

    private Role role(Tenant tenant, RoleStatus status, String... permissions) {
        Role role = Role.builder()
                .name("Rol " + UUID.randomUUID())
                .status(status)
                .permissions(List.of(permissions))
                .build();
        role.setTenantId(tenant.getId());
        return roleRepository.save(role);
    }

    private User user(Tenant tenant, UserType type, UserStatus status, Role role) {
        User user = User.builder()
                .name("Usuario test")
                .email("user-" + UUID.randomUUID() + "@test.local")
                .type(type)
                .status(status)
                .roleId(role == null ? null : role.getId())
                .build();
        user.setTenantId(tenant.getId());
        return userRepository.save(user);
    }

    private Session session(User user, boolean rememberMe) {
        return sessionRepository.save(Session.builder()
                .userId(user.getId())
                .rememberMe(rememberMe)
                .expiresAt(Instant.now().plus(1, ChronoUnit.HOURS).truncatedTo(ChronoUnit.SECONDS))
                .build());
    }

    private String tokenFor(User user) {
        return jwtService.generateToken(user, session(user, false));
    }
}
