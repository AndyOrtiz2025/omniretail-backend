package com.omniretail.backend.administration.controller;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.omniretail.backend.TestcontainersConfiguration;
import com.omniretail.backend.administration.entity.Branch;
import com.omniretail.backend.administration.entity.BranchStatus;
import com.omniretail.backend.administration.entity.BranchType;
import com.omniretail.backend.administration.entity.Role;
import com.omniretail.backend.administration.entity.Tenant;
import com.omniretail.backend.administration.entity.TenantStatus;
import com.omniretail.backend.administration.entity.User;
import com.omniretail.backend.administration.entity.UserStatus;
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
class UserControllerTest {

    private static final String BASE_URL = "/api/v1/administration/users";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JwtService jwtService;

    @Autowired
    private TenantRepository tenantRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private RoleRepository roleRepository;

    @Autowired
    private BranchRepository branchRepository;

    @Autowired
    private SessionRepository sessionRepository;

    @Test
    void withoutTokenReturnsUnauthorized() throws Exception {
        mockMvc.perform(get(BASE_URL)).andExpect(status().isUnauthorized());
    }

    @Test
    void withoutPermissionReturnsForbidden() throws Exception {
        Tenant tenant = persistTenant();
        String token = tokenFor(tenant, List.of());

        mockMvc.perform(get(BASE_URL).header("Authorization", bearer(token)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("ACCESS_DENIED"));
    }

    @Test
    void createUserSuccess() throws Exception {
        Tenant tenant = persistTenant();
        String token = tokenFor(tenant);

        String body = """
                {"name":"Empleado Nuevo","email":"NUEVO@Omniretail.Local","type":"employee","employeeCode":"EMP-001"}
                """;

        mockMvc.perform(post(BASE_URL)
                        .header("Authorization", bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.name").value("Empleado Nuevo"))
                .andExpect(jsonPath("$.email").value("nuevo@omniretail.local"))
                .andExpect(jsonPath("$.employeeCode").value("EMP-001"))
                .andExpect(jsonPath("$.type").value("employee"))
                .andExpect(jsonPath("$.status").value("active"))
                .andExpect(jsonPath("$.tenantId").value(tenant.getId().toString()));
    }

    @Test
    void createUserDuplicateEmailConflict() throws Exception {
        Tenant tenant = persistTenant();
        String token = tokenFor(tenant);

        String body = """
                {"name":"Empleado Uno","email":"duplicado@omniretail.local","type":"employee"}
                """;

        mockMvc.perform(post(BASE_URL)
                        .header("Authorization", bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isCreated());

        String secondBody = """
                {"name":"Empleado Dos","email":"DUPLICADO@omniretail.local","type":"employee"}
                """;

        mockMvc.perform(post(BASE_URL)
                        .header("Authorization", bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(secondBody))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("USER_EMAIL_EXISTS"));
    }

    @Test
    void createUserDuplicateEmployeeCodeConflict() throws Exception {
        Tenant tenant = persistTenant();
        String token = tokenFor(tenant);

        String firstBody = """
                {"name":"Empleado Uno","email":"empleado-uno@omniretail.local","type":"employee","employeeCode":"EMP-100"}
                """;

        mockMvc.perform(post(BASE_URL)
                        .header("Authorization", bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(firstBody))
                .andExpect(status().isCreated());

        String secondBody = """
                {"name":"Empleado Dos","email":"empleado-dos@omniretail.local","type":"employee","employeeCode":"emp-100"}
                """;

        mockMvc.perform(post(BASE_URL)
                        .header("Authorization", bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(secondBody))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("EMPLOYEE_CODE_EXISTS"));
    }

    @Test
    void createUserForeignRoleNotFound() throws Exception {
        Tenant tenantA = persistTenant();
        Tenant tenantB = persistTenant();
        String token = tokenFor(tenantA);

        Role foreignRole = Role.builder().name("Rol Ajeno").permissions(List.of()).build();
        foreignRole.setTenantId(tenantB.getId());
        foreignRole = roleRepository.save(foreignRole);

        String body =
                """
                {"name":"Empleado Nuevo","email":"rol-ajeno@omniretail.local","type":"employee","roleId":"%s"}
                """
                        .formatted(foreignRole.getId());

        mockMvc.perform(post(BASE_URL)
                        .header("Authorization", bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("ROLE_NOT_FOUND"));
    }

    @Test
    void createUserAntiPrivilegeEscalationForbidden() throws Exception {
        Tenant tenant = persistTenant();
        // Puede gestionar usuarios, pero NUNCA tuvo admin.branches.manage: no puede delegarlo.
        String token = tokenFor(tenant, List.of("admin.users.read", "admin.users.manage"));

        Role targetRole = Role.builder()
                .name("Rol Superior")
                .permissions(List.of("admin.branches.manage"))
                .build();
        targetRole.setTenantId(tenant.getId());
        targetRole = roleRepository.save(targetRole);

        String body =
                """
                {"name":"Empleado Nuevo","email":"escalamiento@omniretail.local","type":"employee","roleId":"%s"}
                """
                        .formatted(targetRole.getId());

        mockMvc.perform(post(BASE_URL)
                        .header("Authorization", bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("PERMISSION_NOT_DELEGABLE"));
    }

    @Test
    void createUserForeignBranchNotFound() throws Exception {
        Tenant tenantA = persistTenant();
        Tenant tenantB = persistTenant();
        String token = tokenFor(tenantA);

        Branch foreignBranch = Branch.builder()
                .code("AJENA")
                .name("Sucursal Ajena")
                .type(BranchType.main)
                .status(BranchStatus.active)
                .build();
        foreignBranch.setTenantId(tenantB.getId());
        foreignBranch = branchRepository.save(foreignBranch);

        String body =
                """
                {"name":"Empleado Nuevo","email":"sucursal-ajena@omniretail.local","type":"employee","branchId":"%s"}
                """
                        .formatted(foreignBranch.getId());

        mockMvc.perform(post(BASE_URL)
                        .header("Authorization", bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("BRANCH_NOT_FOUND"));
    }

    @Test
    void crossTenantIsolation() throws Exception {
        Tenant tenantA = persistTenant();
        Tenant tenantB = persistTenant();
        String tokenB = tokenFor(tenantB);

        User user = User.builder()
                .name("Empleado Tenant A")
                .email("aislado@omniretail.local")
                .type(UserType.employee)
                .build();
        user.setTenantId(tenantA.getId());
        user = userRepository.save(user);

        mockMvc.perform(get(BASE_URL + "/" + user.getId()).header("Authorization", bearer(tokenB)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("USER_NOT_FOUND"));

        String updateBody = """
                {"name":"Empleado Editado","status":"active"}
                """;

        mockMvc.perform(put(BASE_URL + "/" + user.getId())
                        .header("Authorization", bearer(tokenB))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(updateBody))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("USER_NOT_FOUND"));
    }

    @Test
    void updateUserSuccess() throws Exception {
        Tenant tenant = persistTenant();
        String token = tokenFor(tenant);

        User user = User.builder()
                .name("Nombre Original")
                .email("original@omniretail.local")
                .type(UserType.employee)
                .build();
        user.setTenantId(tenant.getId());
        user = userRepository.save(user);

        String updateBody = """
                {"name":"Nombre Actualizado","phone":"22345678","employeeCode":"EMP-200","status":"inactive"}
                """;

        mockMvc.perform(put(BASE_URL + "/" + user.getId())
                        .header("Authorization", bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(updateBody))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("Nombre Actualizado"))
                .andExpect(jsonPath("$.phone").value("22345678"))
                .andExpect(jsonPath("$.employeeCode").value("EMP-200"))
                .andExpect(jsonPath("$.status").value("inactive"))
                .andExpect(jsonPath("$.email").value("original@omniretail.local"))
                .andExpect(jsonPath("$.type").value("employee"));
    }

    @Test
    void updateUserStatusSuccess() throws Exception {
        Tenant tenant = persistTenant();
        String token = tokenFor(tenant);

        User user = User.builder()
                .name("Empleado Estado")
                .email("estado@omniretail.local")
                .type(UserType.employee)
                .build();
        user.setTenantId(tenant.getId());
        user = userRepository.save(user);

        mockMvc.perform(put(BASE_URL + "/" + user.getId() + "/status")
                        .header("Authorization", bearer(token))
                        .param("status", "inactive"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("inactive"));

        mockMvc.perform(put(BASE_URL + "/" + user.getId() + "/status")
                        .header("Authorization", bearer(token))
                        .param("status", "archived"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("archived"));
    }

    @Test
    void listUsersWithFiltersAndPagination() throws Exception {
        Tenant tenant = persistTenant();
        String token = tokenFor(tenant);

        persistUser(tenant, "empleado-activo@omniretail.local", UserType.employee, UserStatus.active);
        persistUser(tenant, "empleado-inactivo@omniretail.local", UserType.employee, UserStatus.inactive);
        persistUser(tenant, "cliente-activo@omniretail.local", UserType.customer, UserStatus.active);

        mockMvc.perform(get(BASE_URL)
                        .header("Authorization", bearer(token))
                        .param("type", "employee")
                        .param("status", "active"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.items[0].email").value("empleado-activo@omniretail.local"))
                .andExpect(jsonPath("$.page").value(1))
                .andExpect(jsonPath("$.pageSize").value(20))
                .andExpect(jsonPath("$.totalItems").value(1));

        mockMvc.perform(get(BASE_URL).header("Authorization", bearer(token)).param("type", "employee"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(2))
                .andExpect(jsonPath("$.totalItems").value(2));
    }

    private User persistUser(Tenant tenant, String email, UserType type, UserStatus status) {
        User user = User.builder()
                .name("Usuario " + email)
                .email(email)
                .type(type)
                .status(status)
                .build();
        user.setTenantId(tenant.getId());
        return userRepository.save(user);
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
        return tenantRepository.save(tenant);
    }

    private String tokenFor(Tenant tenant) {
        return tokenFor(tenant, List.of("admin.users.read", "admin.users.manage"));
    }

    private String tokenFor(Tenant tenant, List<String> permissions) {
        Role actorRole = Role.builder()
                .name("Rol Actor " + UUID.randomUUID())
                .permissions(permissions)
                .build();
        actorRole.setTenantId(tenant.getId());
        actorRole = roleRepository.save(actorRole);

        User user = User.builder()
                .name("Empleado Demo")
                .email("empleado-" + UUID.randomUUID() + "@omniretail.local")
                .type(UserType.employee)
                .roleId(actorRole.getId())
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
