package com.omniretail.backend.administration.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
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
import com.omniretail.backend.administration.entity.UserStatus;
import com.omniretail.backend.administration.entity.UserType;
import com.omniretail.backend.administration.repository.BranchRepository;
import com.omniretail.backend.administration.repository.RoleRepository;
import com.omniretail.backend.administration.repository.TenantRepository;
import com.omniretail.backend.administration.repository.UserRepository;
import com.omniretail.backend.auth.entity.AccountStatus;
import com.omniretail.backend.auth.entity.AuthAccount;
import com.omniretail.backend.auth.entity.EmployeeInvitation;
import com.omniretail.backend.auth.entity.Session;
import com.omniretail.backend.auth.repository.AuthAccountRepository;
import com.omniretail.backend.auth.repository.EmployeeInvitationRepository;
import com.omniretail.backend.auth.repository.SessionRepository;
import com.omniretail.backend.auth.service.JwtService;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
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

    @Autowired private SaasPlanRepository planRepository;
    @Autowired private TenantSubscriptionRepository subscriptionRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private RoleRepository roleRepository;

    @Autowired
    private BranchRepository branchRepository;

    @Autowired
    private SessionRepository sessionRepository;

    @Autowired
    private AuthAccountRepository accountRepository;

    @Autowired
    private EmployeeInvitationRepository invitationRepository;

    // Escritas antes de las rutas; RED/GREEN no ejecutados por restriccion explicita.
    @ParameterizedTest
    @ValueSource(strings = {"invite", "resend-invite"})
    void invitationWithoutTokenReturnsUnauthorized(String endpoint) throws Exception {
        mockMvc.perform(post(BASE_URL + "/" + UUID.randomUUID() + "/" + endpoint))
                .andExpect(status().isUnauthorized());
    }

    @ParameterizedTest
    @ValueSource(strings = {"invite", "resend-invite"})
    void invitationWithoutManagePermissionReturnsForbidden(String endpoint) throws Exception {
        Tenant tenant = persistTenant();
        String token = tokenFor(tenant, List.of("admin.users.read"));
        Role role = persistRole(tenant, List.of());
        User employee = persistEmployee(tenant, "no-invite-" + UUID.randomUUID() + "@test.local", role.getId());

        mockMvc.perform(post(BASE_URL + "/" + employee.getId() + "/" + endpoint)
                        .header("Authorization", bearer(token)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("ACCESS_DENIED"));

        assertThat(accountRepository.findByUserId(employee.getId())).isEmpty();
        assertThat(invitationRepository.findTopByUserIdOrderByCreatedAtDesc(employee.getId())).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {"invite", "resend-invite"})
    void invitationCreatesPendingAccountAndReturnsToken(String endpoint) throws Exception {
        Tenant tenant = persistTenant();
        String token = tokenFor(tenant, List.of("admin.users.manage"));
        Role role = persistRole(tenant, List.of());
        User employee = persistEmployee(tenant, "invite-" + UUID.randomUUID() + "@test.local", role.getId());

        String response = mockMvc.perform(post(BASE_URL + "/" + employee.getId() + "/" + endpoint)
                        .header("Authorization", bearer(token)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.userId").value(employee.getId().toString()))
                .andExpect(jsonPath("$.invitationToken").isNotEmpty())
                .andExpect(jsonPath("$.expiresAt").isNotEmpty())
                .andReturn().getResponse().getContentAsString();

        String invitationToken = JsonPath.read(response, "$.invitationToken");
        String expiresAt = JsonPath.read(response, "$.expiresAt");
        assertThat(invitationToken).matches("[A-Za-z0-9_-]{43}");
        EmployeeInvitation invitation = invitationRepository
                .findTopByUserIdOrderByCreatedAtDesc(employee.getId()).orElseThrow();
        assertThat(invitation.getTenantId()).isEqualTo(tenant.getId());
        // PostgreSQL conserva microsegundos; la respuesta puede contener nanosegundos.
        assertThat(Duration.between(Instant.parse(expiresAt), invitation.getExpiresAt()).abs())
                .isLessThan(Duration.ofNanos(1_001));
        assertThat(invitation.getAcceptedAt()).isNull();
        assertThat(invitation.getSupersededAt()).isNull();
        assertThat(accountRepository.findByUserId(employee.getId()).orElseThrow().getStatus())
                .isEqualTo(AccountStatus.password_reset_required);
    }

    @Test
    void resendInvitationReplacesTokenAndSupersedesPreviousInvitation() throws Exception {
        Tenant tenant = persistTenant();
        String token = tokenFor(tenant);
        Role role = persistRole(tenant, List.of());
        User employee = persistEmployee(tenant, "reinvite-" + UUID.randomUUID() + "@test.local", role.getId());

        String firstResponse = mockMvc.perform(post(BASE_URL + "/" + employee.getId() + "/invite")
                        .header("Authorization", bearer(token)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        String firstToken = JsonPath.read(firstResponse, "$.invitationToken");
        EmployeeInvitation previous = invitationRepository
                .findTopByUserIdOrderByCreatedAtDesc(employee.getId()).orElseThrow();

        String nextResponse = mockMvc.perform(post(BASE_URL + "/" + employee.getId() + "/resend-invite")
                        .header("Authorization", bearer(token)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.userId").value(employee.getId().toString()))
                .andExpect(jsonPath("$.invitationToken").isNotEmpty())
                .andExpect(jsonPath("$.expiresAt").isNotEmpty())
                .andReturn().getResponse().getContentAsString();
        String nextToken = JsonPath.read(nextResponse, "$.invitationToken");
        assertThat(nextToken).matches("[A-Za-z0-9_-]{43}").isNotEqualTo(firstToken);

        EmployeeInvitation superseded = invitationRepository.findById(previous.getId()).orElseThrow();
        EmployeeInvitation latest = invitationRepository
                .findTopByUserIdOrderByCreatedAtDesc(employee.getId()).orElseThrow();
        assertThat(superseded.getSupersededAt()).isNotNull().isEqualTo(latest.getCreatedAt());
        assertThat(superseded.getAcceptedAt()).isNull();
        assertThat(latest.getId()).isNotEqualTo(previous.getId());
        assertThat(latest.getTokenHash()).isNotEqualTo(previous.getTokenHash());
        assertThat(latest.getSupersededAt()).isNull();
        assertThat(latest.getAcceptedAt()).isNull();
        String expiresAt = JsonPath.read(nextResponse, "$.expiresAt");
        assertThat(Duration.between(Instant.parse(expiresAt), latest.getExpiresAt()).abs())
                .isLessThan(Duration.ofNanos(1_001));
    }

    @ParameterizedTest
    @ValueSource(strings = {"invite", "resend-invite"})
    void invitationForAnotherTenantReturnsUserNotFound(String endpoint) throws Exception {
        Tenant employeeTenant = persistTenant();
        Role role = persistRole(employeeTenant, List.of());
        User employee = persistEmployee(employeeTenant,
                "foreign-invite-" + UUID.randomUUID() + "@test.local", role.getId());
        String token = tokenFor(persistTenant());

        mockMvc.perform(post(BASE_URL + "/" + employee.getId() + "/" + endpoint)
                        .header("Authorization", bearer(token))
                        .param("tenantId", employeeTenant.getId().toString()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("USER_NOT_FOUND"));

        assertThat(accountRepository.findByUserId(employee.getId())).isEmpty();
        assertThat(invitationRepository.findTopByUserIdOrderByCreatedAtDesc(employee.getId())).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {"invite", "resend-invite"})
    void invitationForActiveAccountReturnsConflict(String endpoint) throws Exception {
        Tenant tenant = persistTenant();
        String token = tokenFor(tenant);
        Role role = persistRole(tenant, List.of());
        User employee = persistEmployee(tenant, "active-invite-" + UUID.randomUUID() + "@test.local", role.getId());
        persistAccount(employee, AccountStatus.active);

        mockMvc.perform(post(BASE_URL + "/" + employee.getId() + "/" + endpoint)
                        .header("Authorization", bearer(token)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("ACCOUNT_ALREADY_ACTIVE"));

        assertThat(invitationRepository.findTopByUserIdOrderByCreatedAtDesc(employee.getId())).isEmpty();
        assertThat(accountRepository.findByUserId(employee.getId()).orElseThrow().getStatus())
                .isEqualTo(AccountStatus.active);
    }

    @ParameterizedTest
    @ValueSource(strings = {"invite", "resend-invite"})
    void invitationForIneligibleAccountReturnsBadRequest(String endpoint) throws Exception {
        Tenant tenant = persistTenant();
        String token = tokenFor(tenant);
        Role role = persistRole(tenant, List.of());
        User employee = persistEmployee(tenant, "disabled-invite-" + UUID.randomUUID() + "@test.local", role.getId());
        persistAccount(employee, AccountStatus.disabled);

        mockMvc.perform(post(BASE_URL + "/" + employee.getId() + "/" + endpoint)
                        .header("Authorization", bearer(token)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVITATION_NOT_ALLOWED"));

        assertThat(invitationRepository.findTopByUserIdOrderByCreatedAtDesc(employee.getId())).isEmpty();
        assertThat(accountRepository.findByUserId(employee.getId()).orElseThrow().getStatus())
                .isEqualTo(AccountStatus.disabled);
    }

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
    void emptyAuthSummariesReturnOkWithoutEmployees() throws Exception {
        Tenant tenant = persistTenant();
        String token = tokenFor(tenant, List.of("admin.users.read"));

        mockMvc.perform(post(BASE_URL + "/auth-summaries")
                        .header("Authorization", bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"userIds\":[]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isEmpty());
    }

    @Test
    void createUserSuccess() throws Exception {
        Tenant tenant = persistTenant();
        String token = tokenFor(tenant);
        Role role = persistRole(tenant, List.of());

        String body =
                """
                {"name":"Empleado Nuevo","email":"NUEVO@Omniretail.Local","phone":"+502 2323-1232","employeeCode":"EMP-001","roleId":"%s"}
                """
                        .formatted(role.getId());

        mockMvc.perform(post(BASE_URL)
                        .header("Authorization", bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.name").value("Empleado Nuevo"))
                .andExpect(jsonPath("$.email").value("nuevo@omniretail.local"))
                .andExpect(jsonPath("$.phone").value("+502 2323-1232"))
                .andExpect(jsonPath("$.employeeCode").value("EMP-001"))
                .andExpect(jsonPath("$.type").value("employee"))
                .andExpect(jsonPath("$.status").value("active"))
                .andExpect(jsonPath("$.roleId").value(role.getId().toString()))
                .andExpect(jsonPath("$.tenantId").value(tenant.getId().toString()));
    }

    @Test
    void createEmployeeWithMaxEmailLengthSucceeds() throws Exception {
        Tenant tenant = persistTenant();
        String token = tokenFor(tenant);
        Role role = persistRole(tenant, List.of());
        // 64 (local) + 1 (@) + 61 + 1 + 61 + 1 + 60 + 5 (.demo) = 254, el maximo de RFC 5321.
        String email254 = "e".repeat(64) + "@" + "d".repeat(61) + "." + "d".repeat(61) + "." + "d".repeat(60) + ".demo";

        String body =
                """
                {"name":"Empleado 254","email":"%s","employeeCode":"EMP-254","roleId":"%s"}
                """
                        .formatted(email254, role.getId());

        mockMvc.perform(post(BASE_URL)
                        .header("Authorization", bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.email").value(email254));
    }

    @Test
    void createUserDuplicateEmailConflict() throws Exception {
        Tenant tenant = persistTenant();
        String token = tokenFor(tenant);
        Role role = persistRole(tenant, List.of());

        String body =
                """
                {"name":"Empleado Uno","email":"duplicado@omniretail.local","employeeCode":"EMP-101","roleId":"%s"}
                """
                        .formatted(role.getId());

        mockMvc.perform(post(BASE_URL)
                        .header("Authorization", bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isCreated());

        String secondBody =
                """
                {"name":"Empleado Dos","email":"DUPLICADO@omniretail.local","employeeCode":"EMP-102","roleId":"%s"}
                """
                        .formatted(role.getId());

        mockMvc.perform(post(BASE_URL)
                        .header("Authorization", bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(secondBody))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("USER_EMAIL_EXISTS"));
    }

    @Test
    void createUserEmailFromAnotherTenantConflict() throws Exception {
        Tenant tenantA = persistTenant();
        Role roleA = persistRole(tenantA, List.of());
        persistEmployee(tenantA, "compartido@omniretail.local", roleA.getId());

        Tenant tenantB = persistTenant();
        String tokenB = tokenFor(tenantB);
        Role roleB = persistRole(tenantB, List.of());

        String body =
                """
                {"name":"Empleado Nuevo","email":"COMPARTIDO@omniretail.local","employeeCode":"EMP-XT","roleId":"%s"}
                """
                        .formatted(roleB.getId());

        mockMvc.perform(post(BASE_URL)
                        .header("Authorization", bearer(tokenB))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("USER_EMAIL_EXISTS"));
    }

    @Test
    void createUserDuplicateEmployeeCodeConflict() throws Exception {
        Tenant tenant = persistTenant();
        String token = tokenFor(tenant);
        Role role = persistRole(tenant, List.of());

        String firstBody =
                """
                {"name":"Empleado Uno","email":"empleado-uno@omniretail.local","employeeCode":"EMP-100","roleId":"%s"}
                """
                        .formatted(role.getId());

        mockMvc.perform(post(BASE_URL)
                        .header("Authorization", bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(firstBody))
                .andExpect(status().isCreated());

        String secondBody =
                """
                {"name":"Empleado Dos","email":"empleado-dos@omniretail.local","employeeCode":"emp-100","roleId":"%s"}
                """
                        .formatted(role.getId());

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
                {"name":"Empleado Nuevo","email":"rol-ajeno@omniretail.local","employeeCode":"EMP-110","roleId":"%s"}
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
                {"name":"Empleado Nuevo","email":"escalamiento@omniretail.local","employeeCode":"EMP-111","roleId":"%s"}
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
        Role role = persistRole(tenantA, List.of());

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
                {"name":"Empleado Nuevo","email":"sucursal-ajena@omniretail.local","employeeCode":"EMP-112","roleId":"%s","branchId":"%s"}
                """
                        .formatted(role.getId(), foreignBranch.getId());

        mockMvc.perform(post(BASE_URL)
                        .header("Authorization", bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("BRANCH_NOT_FOUND"));
    }

    @Test
    void createUserWithoutRoleIdReturnsBadRequest() throws Exception {
        Tenant tenant = persistTenant();
        String token = tokenFor(tenant);

        String body = """
                {"name":"Empleado Nuevo","email":"sin-rol@omniretail.local","employeeCode":"EMP-300"}
                """;

        mockMvc.perform(post(BASE_URL)
                        .header("Authorization", bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest());
    }

    @Test
    void createUserWithoutEmployeeCodeReturnsBadRequest() throws Exception {
        Tenant tenant = persistTenant();
        String token = tokenFor(tenant);
        Role role = persistRole(tenant, List.of());

        String body =
                """
                {"name":"Empleado Nuevo","email":"sin-codigo@omniretail.local","roleId":"%s"}
                """
                        .formatted(role.getId());

        mockMvc.perform(post(BASE_URL)
                        .header("Authorization", bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest());
    }

    @Test
    void invalidUserPhoneReturnsValidationField() throws Exception {
        Tenant tenant = persistTenant();
        String token = tokenFor(tenant);
        Role role = persistRole(tenant, List.of());

        String body =
                """
                {"name":"Empleado Inválido","email":"telefono-invalido@omniretail.local","phone":"+502 2323-12321","employeeCode":"EMP-PHONE","roleId":"%s"}
                """
                        .formatted(role.getId());

        mockMvc.perform(post(BASE_URL)
                        .header("Authorization", bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.fields.phone").exists());
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

        String updateBody =
                """
                {"name":"Empleado Editado","employeeCode":"EMP-999","roleId":"%s","status":"active"}
                """
                        .formatted(UUID.randomUUID());

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
        Role role = persistRole(tenant, List.of());
        User user = persistEmployee(tenant, "original@omniretail.local", role.getId(), "EMP-200", UserStatus.active, List.of());

        String updateBody =
                """
                {"name":"Nombre Actualizado","phone":"+502 2323-1232","employeeCode":"EMP-200","roleId":"%s","status":"inactive"}
                """
                        .formatted(role.getId());

        mockMvc.perform(put(BASE_URL + "/" + user.getId())
                        .header("Authorization", bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(updateBody))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("Nombre Actualizado"))
                .andExpect(jsonPath("$.phone").value("+502 2323-1232"))
                .andExpect(jsonPath("$.employeeCode").value("EMP-200"))
                .andExpect(jsonPath("$.status").value("inactive"))
                .andExpect(jsonPath("$.email").value("original@omniretail.local"))
                .andExpect(jsonPath("$.type").value("employee"));
    }

    @Test
    void updateUserStatusSuccess() throws Exception {
        Tenant tenant = persistTenant();
        String token = tokenFor(tenant);
        Role role = persistRole(tenant, List.of());
        User user = persistEmployee(tenant, "estado@omniretail.local", role.getId());

        mockMvc.perform(put(BASE_URL + "/" + user.getId() + "/status")
                        .header("Authorization", bearer(token))
                        .param("status", "inactive"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("inactive"));

        mockMvc.perform(put(BASE_URL + "/" + user.getId() + "/status")
                        .header("Authorization", bearer(token))
                        .param("status", "blocked"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("blocked"));
    }

    @Test
    void archivedStatusOnEditIsRejected() throws Exception {
        Tenant tenant = persistTenant();
        String token = tokenFor(tenant);
        Role role = persistRole(tenant, List.of());
        User user = persistEmployee(tenant, "archivar@omniretail.local", role.getId(), "EMP-ARC", UserStatus.active, List.of());

        String updateBody =
                """
                {"name":"Empleado","employeeCode":"EMP-ARC","roleId":"%s","status":"archived"}
                """
                        .formatted(role.getId());

        mockMvc.perform(put(BASE_URL + "/" + user.getId())
                        .header("Authorization", bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(updateBody))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("STATUS_ARCHIVED_NOT_ALLOWED"));

        mockMvc.perform(put(BASE_URL + "/" + user.getId() + "/status")
                        .header("Authorization", bearer(token))
                        .param("status", "archived"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("STATUS_ARCHIVED_NOT_ALLOWED"));
    }

    @Test
    void updateUserKeepsPreviouslyAssignedArchivedBranch() throws Exception {
        Tenant tenant = persistTenant();
        String token = tokenFor(tenant);
        Role role = persistRole(tenant, List.of());
        Branch branch = persistBranch(tenant, "SUC-1", BranchStatus.active);
        User user = persistEmployee(
                tenant, "sucursal-conservada@omniretail.local", role.getId(), "EMP-SUC", UserStatus.active, List.of(branch.getId()));

        branch.setStatus(BranchStatus.archived);
        branchRepository.save(branch);

        String updateBody =
                """
                {"name":"Empleado Sucursal","employeeCode":"EMP-SUC","roleId":"%s","allowedBranchIds":["%s"],"status":"active"}
                """
                        .formatted(role.getId(), branch.getId());

        mockMvc.perform(put(BASE_URL + "/" + user.getId())
                        .header("Authorization", bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(updateBody))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.allowedBranchIds[0]").value(branch.getId().toString()));
    }

    @Test
    void listExcludesCustomersAndGetByIdOfCustomerReturnsNotFound() throws Exception {
        Tenant tenant = persistTenant();
        String token = tokenFor(tenant);
        Role role = persistRole(tenant, List.of());
        persistEmployee(tenant, "empleado-solo@omniretail.local", role.getId());

        User customer = User.builder()
                .name("Cliente Demo")
                .email("cliente@omniretail.local")
                .type(UserType.customer)
                .status(UserStatus.active)
                .build();
        customer.setTenantId(tenant.getId());
        customer = userRepository.save(customer);

        // El actor de tokenFor() ya es un empleado, así que el listado trae actor + el nuevo: 2, nunca el cliente.
        mockMvc.perform(get(BASE_URL).header("Authorization", bearer(token)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalItems").value(2));

        mockMvc.perform(get(BASE_URL + "/" + customer.getId()).header("Authorization", bearer(token)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("USER_NOT_FOUND"));
    }

    @Test
    void listUsersWithFiltersAndPagination() throws Exception {
        Tenant tenant = persistTenant();
        String token = tokenFor(tenant);
        Role role = persistRole(tenant, List.of());

        persistEmployee(tenant, "empleado-activo@omniretail.local", role.getId(), "EMP-A1", UserStatus.active, List.of());
        persistEmployee(tenant, "empleado-inactivo@omniretail.local", role.getId(), "EMP-A2", UserStatus.inactive, List.of());

        User customer = User.builder()
                .name("Cliente Demo")
                .email("cliente-filtro@omniretail.local")
                .type(UserType.customer)
                .status(UserStatus.active)
                .build();
        customer.setTenantId(tenant.getId());
        userRepository.save(customer);

        // actor de tokenFor() (active) + empleado-activo = 2.
        mockMvc.perform(
                        get(BASE_URL).header("Authorization", bearer(token)).param("status", "active"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(2))
                .andExpect(jsonPath("$.page").value(1))
                .andExpect(jsonPath("$.pageSize").value(20))
                .andExpect(jsonPath("$.totalItems").value(2));

        // actor + empleado-activo + empleado-inactivo = 3, sin el cliente.
        mockMvc.perform(get(BASE_URL).header("Authorization", bearer(token)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalItems").value(3));
    }

    @Test
    void blockingEmployeeInvalidatesToken() throws Exception {
        Tenant tenant = persistTenant();
        String adminToken = tokenFor(tenant);
        Role employeeRole = persistRole(tenant, List.of("admin.users.read"));
        User employee = persistEmployee(tenant, "bloqueado@omniretail.local", employeeRole.getId());
        String employeeToken = tokenForUser(employee);

        mockMvc.perform(get(BASE_URL).header("Authorization", bearer(employeeToken)))
                .andExpect(status().isOk());

        mockMvc.perform(put(BASE_URL + "/" + employee.getId() + "/status")
                        .header("Authorization", bearer(adminToken))
                        .param("status", "blocked"))
                .andExpect(status().isOk());

        mockMvc.perform(get(BASE_URL).header("Authorization", bearer(employeeToken)))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void changingRoleRevokesSessions() throws Exception {
        Tenant tenant = persistTenant();
        String adminToken = tokenFor(tenant);
        Role originalRole = persistRole(tenant, List.of("admin.users.read"));
        Role newRole = persistRole(tenant, List.of("admin.users.read"));
        User employee = persistEmployee(tenant, "cambio-rol@omniretail.local", originalRole.getId());
        String employeeToken = tokenForUser(employee);

        mockMvc.perform(get(BASE_URL).header("Authorization", bearer(employeeToken)))
                .andExpect(status().isOk());

        String updateBody =
                """
                {"name":"%s","employeeCode":"%s","roleId":"%s","status":"active"}
                """
                        .formatted(employee.getName(), employee.getEmployeeCode(), newRole.getId());

        mockMvc.perform(put(BASE_URL + "/" + employee.getId())
                        .header("Authorization", bearer(adminToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(updateBody))
                .andExpect(status().isOk());

        assertTrue(sessionRepository
                .findByUserIdAndRevokedAtIsNull(employee.getId())
                .isEmpty());

        mockMvc.perform(get(BASE_URL).header("Authorization", bearer(employeeToken)))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void changingBranchesRevokesSessions() throws Exception {
        Tenant tenant = persistTenant();
        String adminToken = tokenFor(tenant);
        Role role = persistRole(tenant, List.of("admin.users.read"));
        Branch branchA = persistBranch(tenant, "SUC-A", BranchStatus.active);
        Branch branchB = persistBranch(tenant, "SUC-B", BranchStatus.active);
        User employee = persistEmployee(
                tenant, "cambio-sucursal@omniretail.local", role.getId(), "EMP-BR", UserStatus.active, List.of(branchA.getId()));
        String employeeToken = tokenForUser(employee);

        mockMvc.perform(get(BASE_URL).header("Authorization", bearer(employeeToken)))
                .andExpect(status().isOk());

        String updateBody =
                """
                {"name":"%s","employeeCode":"EMP-BR","roleId":"%s","allowedBranchIds":["%s"],"status":"active"}
                """
                        .formatted(employee.getName(), role.getId(), branchB.getId());

        mockMvc.perform(put(BASE_URL + "/" + employee.getId())
                        .header("Authorization", bearer(adminToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(updateBody))
                .andExpect(status().isOk());

        assertTrue(sessionRepository
                .findByUserIdAndRevokedAtIsNull(employee.getId())
                .isEmpty());

        mockMvc.perform(get(BASE_URL).header("Authorization", bearer(employeeToken)))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void changingNameOnlyKeepsSessionActive() throws Exception {
        Tenant tenant = persistTenant();
        String adminToken = tokenFor(tenant);
        Role role = persistRole(tenant, List.of("admin.users.read"));
        User employee = persistEmployee(tenant, "solo-nombre@omniretail.local", role.getId());
        String employeeToken = tokenForUser(employee);

        String updateBody =
                """
                {"name":"Nombre Actualizado","employeeCode":"%s","roleId":"%s","status":"active"}
                """
                        .formatted(employee.getEmployeeCode(), role.getId());

        mockMvc.perform(put(BASE_URL + "/" + employee.getId())
                        .header("Authorization", bearer(adminToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(updateBody))
                .andExpect(status().isOk());

        assertFalse(sessionRepository
                .findByUserIdAndRevokedAtIsNull(employee.getId())
                .isEmpty());

        mockMvc.perform(get(BASE_URL).header("Authorization", bearer(employeeToken)))
                .andExpect(status().isOk());
    }

    private void persistAccount(User employee, AccountStatus status) {
        accountRepository.save(AuthAccount.builder()
                .userId(employee.getId())
                .email(employee.getEmail())
                .passwordHash("unused-password-hash")
                .status(status)
                .build());
    }

    private User persistEmployee(Tenant tenant, String email, UUID roleId) {
        return persistEmployee(
                tenant, email, roleId, "EMP-" + UUID.randomUUID().toString().substring(0, 8), UserStatus.active, List.of());
    }

    private User persistEmployee(
            Tenant tenant, String email, UUID roleId, String employeeCode, UserStatus status, List<UUID> allowedBranchIds) {
        User user = User.builder()
                .name("Empleado " + email)
                .email(email)
                .type(UserType.employee)
                .status(status)
                .roleId(roleId)
                .employeeCode(employeeCode)
                .allowedBranchIds(allowedBranchIds)
                .build();
        user.setTenantId(tenant.getId());
        return userRepository.save(user);
    }

    private Role persistRole(Tenant tenant, List<String> permissions) {
        Role role = Role.builder()
                .name("Rol " + UUID.randomUUID())
                .permissions(permissions)
                .build();
        role.setTenantId(tenant.getId());
        return roleRepository.save(role);
    }

    private Branch persistBranch(Tenant tenant, String code, BranchStatus status) {
        Branch branch = Branch.builder()
                .code(code)
                .name("Sucursal " + code)
                .type(BranchType.main)
                .status(status)
                .build();
        branch.setTenantId(tenant.getId());
        return branchRepository.save(branch);
    }

    @Test
    void employeeLimitBlocksNextCreationButNeverEditingOrReactivation() throws Exception {
        Tenant tenant = persistTenant();
        String token = tokenFor(tenant);
        Role role = persistRole(tenant, List.of());
        User existing = persistEmployee(tenant, "inactive-" + UUID.randomUUID() + "@test.local", role.getId(),
                "INACTIVE", UserStatus.inactive, List.of());
        var limited = planRepository.saveAndFlush(com.omniretail.backend.administration.entity.SaasPlan.builder()
                .code("limited-" + UUID.randomUUID()).name("Plan limitado")
                .monthlyQuetzales(new java.math.BigDecimal("199.00"))
                .status(com.omniretail.backend.administration.entity.PlanStatus.active)
                .maxEmployees(1).capabilities(List.of()).build());
        var subscription = subscriptionRepository.findByTenantIdAndStatusIn(tenant.getId(),
                List.of(com.omniretail.backend.administration.entity.TenantSubscriptionStatus.active)).orElseThrow();
        subscription.setPlanId(limited.getId());
        subscriptionRepository.saveAndFlush(subscription);

        mockMvc.perform(post(BASE_URL).header("Authorization", bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"Siguiente","email":"next-%s@test.local","employeeCode":"NEXT","roleId":"%s"}
                                """.formatted(UUID.randomUUID(), role.getId())))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("LIMIT_REACHED"))
                .andExpect(jsonPath("$.message").value("Alcanzaste el límite de tu plan actual."));
        mockMvc.perform(put(BASE_URL + "/" + existing.getId()).header("Authorization", bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"Nombre actualizado","employeeCode":"INACTIVE","roleId":"%s","status":"active"}
                                """.formatted(role.getId())))
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
        return tokenFor(tenant, List.of("admin.users.read", "admin.users.manage"));
    }

    private String tokenFor(Tenant tenant, List<String> permissions) {
        Role actorRole = persistRole(tenant, permissions);

        User user = User.builder()
                .name("Empleado Demo")
                .email("empleado-" + UUID.randomUUID() + "@omniretail.local")
                .type(UserType.employee)
                .roleId(actorRole.getId())
                .build();
        user.setTenantId(tenant.getId());
        user = userRepository.save(user);

        return tokenForUser(user);
    }

    private String tokenForUser(User user) {
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
