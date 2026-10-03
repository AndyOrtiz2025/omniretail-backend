package com.omniretail.backend.auth.controller;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import com.omniretail.backend.TestcontainersConfiguration;
import com.omniretail.backend.administration.entity.Branch;
import com.omniretail.backend.administration.entity.BranchScope;
import com.omniretail.backend.administration.entity.BranchStatus;
import com.omniretail.backend.administration.entity.BranchType;
import com.omniretail.backend.administration.entity.Role;
import com.omniretail.backend.administration.entity.RoleStatus;
import com.omniretail.backend.administration.entity.Tenant;
import com.omniretail.backend.administration.entity.TenantStatus;
import com.omniretail.backend.administration.entity.User;
import com.omniretail.backend.administration.entity.UserType;
import com.omniretail.backend.administration.repository.BranchRepository;
import com.omniretail.backend.administration.repository.RoleRepository;
import com.omniretail.backend.administration.repository.TenantRepository;
import com.omniretail.backend.administration.repository.UserRepository;
import com.omniretail.backend.auth.entity.AccountStatus;
import com.omniretail.backend.auth.entity.AuthAccount;
import com.omniretail.backend.auth.repository.AuthAccountRepository;
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
class ActiveBranchControllerTest {

    private static final String LOGIN = "/api/v1/auth/login";
    private static final String ME = "/api/v1/auth/me";
    private static final String BRANCH = "/api/v1/auth/session/branch";
    private static final String PASSWORD = "Empleado1234!";
    private static final String NOT_ALLOWED_MESSAGE = "La sucursal seleccionada no está autorizada para esta sesión.";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private TenantRepository tenantRepository;

    @Autowired
    private BranchRepository branchRepository;

    @Autowired
    private RoleRepository roleRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private AuthAccountRepository authAccountRepository;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Test
    void employeeSwitchesToAnotherAllowedBranch() throws Exception {
        Tenant tenant = tenant();
        Branch first = branch(tenant, BranchStatus.active);
        Branch second = branch(tenant, BranchStatus.active);
        User employee = employee(tenant, BranchScope.selected, first.getId(), List.of(first.getId(), second.getId()));
        String token = login(employee);
        me(token).andExpect(jsonPath("$.session.activeBranchId").value(first.getId().toString()));

        changeBranch(token, second.getId())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.session.activeBranchId").value(second.getId().toString()))
                .andExpect(jsonPath("$.user.id").value(employee.getId().toString()))
                .andExpect(jsonPath("$.tenant.id").value(tenant.getId().toString()))
                .andExpect(jsonPath("$.role.id").value(employee.getRoleId().toString()));

        me(token)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.session.activeBranchId").value(second.getId().toString()));
    }

    @Test
    void branchOutsideListFromOtherTenantInactiveOrMissingIsNotAllowed() throws Exception {
        Tenant tenant = tenant();
        Branch allowed = branch(tenant, BranchStatus.active);
        Branch inactive = branch(tenant, BranchStatus.inactive);
        Branch notAssigned = branch(tenant, BranchStatus.active);
        Branch foreign = branch(tenant(), BranchStatus.active);
        User employee = employee(tenant, BranchScope.selected, allowed.getId(),
                List.of(allowed.getId(), inactive.getId(), foreign.getId()));
        String token = login(employee);

        for (UUID branchId : List.of(notAssigned.getId(), foreign.getId(), inactive.getId(), UUID.randomUUID())) {
            expectNotAllowed(changeBranch(token, branchId));
        }
        expectNotAllowed(changeBody(token, "{\"branchId\": null}"));
        expectNotAllowed(changeBody(token, "{}"));

        me(token).andExpect(jsonPath("$.session.activeBranchId").value(allowed.getId().toString()));
    }

    @Test
    void roleWithAllBranchesDoesNotWidenTheSelector() throws Exception {
        Tenant tenant = tenant();
        Branch assigned = branch(tenant, BranchStatus.active);
        Branch other = branch(tenant, BranchStatus.active);
        User admin = employee(tenant, BranchScope.all, assigned.getId(), List.of(assigned.getId()));
        String token = login(admin);

        expectNotAllowed(changeBranch(token, other.getId()));
        changeBranch(token, assigned.getId()).andExpect(status().isOk());
    }

    @Test
    void withoutAssignedListFallsBackToUserBranch() throws Exception {
        Tenant tenant = tenant();
        Branch own = branch(tenant, BranchStatus.active);
        Branch other = branch(tenant, BranchStatus.active);
        User employee = employee(tenant, BranchScope.assigned, own.getId(), null);
        String token = login(employee);

        changeBranch(token, own.getId())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.session.activeBranchId").value(own.getId().toString()));
        expectNotAllowed(changeBranch(token, other.getId()));
    }

    @Test
    void changeOnlyAffectsTheCurrentSession() throws Exception {
        Tenant tenant = tenant();
        Branch first = branch(tenant, BranchStatus.active);
        Branch second = branch(tenant, BranchStatus.active);
        User employee = employee(tenant, BranchScope.selected, first.getId(), List.of(first.getId(), second.getId()));
        String current = login(employee);
        String other = login(employee);

        changeBranch(current, second.getId()).andExpect(status().isOk());

        me(current).andExpect(jsonPath("$.session.activeBranchId").value(second.getId().toString()));
        me(other).andExpect(jsonPath("$.session.activeBranchId").value(first.getId().toString()));
    }

    @Test
    void customerIsForbidden() throws Exception {
        Tenant tenant = tenant();
        Branch branch = branch(tenant, BranchStatus.active);
        User customer = user(tenant, UserType.customer, null, branch.getId(), List.of(branch.getId()));
        String body = mockMvc.perform(post(LOGIN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\": \"%s\", \"password\": \"%s\", \"tenantSlug\": \"%s\"}"
                                .formatted(customer.getEmail(), PASSWORD, tenant.getSlug())))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        expectNotAllowed(changeBranch(JsonPath.read(body, "$.token"), branch.getId()));
    }

    @Test
    void extraFieldsAreRejected() throws Exception {
        Tenant tenant = tenant();
        Branch first = branch(tenant, BranchStatus.active);
        Branch second = branch(tenant, BranchStatus.active);
        User employee = employee(tenant, BranchScope.selected, first.getId(), List.of(first.getId(), second.getId()));
        String token = login(employee);

        changeBody(token, "{\"branchId\": \"%s\", \"sessionId\": \"%s\"}".formatted(second.getId(), UUID.randomUUID()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.fields.sessionId").value("Campo no permitido."));

        me(token).andExpect(jsonPath("$.session.activeBranchId").value(first.getId().toString()));
    }

    @Test
    void withoutTokenIsUnauthorized() throws Exception {
        mockMvc.perform(patch(BRANCH)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"branchId\": \"%s\"}".formatted(UUID.randomUUID())))
                .andExpect(status().isUnauthorized());
    }

    // --- Utilidades ---

    private ResultActions changeBranch(String token, UUID branchId) throws Exception {
        return changeBody(token, "{\"branchId\": \"%s\"}".formatted(branchId));
    }

    private ResultActions changeBody(String token, String body) throws Exception {
        return mockMvc.perform(patch(BRANCH)
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body));
    }

    private static void expectNotAllowed(ResultActions result) throws Exception {
        result.andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("BRANCH_NOT_ALLOWED"))
                .andExpect(jsonPath("$.message").value(NOT_ALLOWED_MESSAGE));
    }

    private ResultActions me(String token) throws Exception {
        return mockMvc.perform(get(ME).header("Authorization", "Bearer " + token));
    }

    private String login(User employee) throws Exception {
        String body = mockMvc.perform(post(LOGIN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\": \"%s\", \"password\": \"%s\"}".formatted(employee.getEmail(), PASSWORD)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return JsonPath.read(body, "$.token");
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

    private Branch branch(Tenant tenant, BranchStatus status) {
        Branch branch = Branch.builder()
                .code("BR-" + UUID.randomUUID().toString().substring(0, 8))
                .name("Sucursal test")
                .type(BranchType.store)
                .status(status)
                .build();
        branch.setTenantId(tenant.getId());
        return branchRepository.save(branch);
    }

    private User employee(Tenant tenant, BranchScope scope, UUID branchId, List<UUID> allowedBranchIds) {
        Role role = Role.builder()
                .name("Rol " + UUID.randomUUID())
                .status(RoleStatus.active)
                .branchScope(scope)
                .permissions(List.of())
                .build();
        role.setTenantId(tenant.getId());
        role = roleRepository.save(role);
        return user(tenant, UserType.employee, role.getId(), branchId, allowedBranchIds);
    }

    private User user(Tenant tenant, UserType type, UUID roleId, UUID branchId, List<UUID> allowedBranchIds) {
        String email = "usuario-" + UUID.randomUUID() + "@test.local";
        User user = User.builder()
                .name("Usuario test")
                .email(email)
                .type(type)
                .roleId(roleId)
                .branchId(branchId)
                .allowedBranchIds(allowedBranchIds)
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
