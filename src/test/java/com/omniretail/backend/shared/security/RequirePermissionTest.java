package com.omniretail.backend.shared.security;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.omniretail.backend.TestcontainersConfiguration;
import com.omniretail.backend.administration.entity.Role;
import com.omniretail.backend.administration.entity.RoleStatus;
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
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/** En perfil test no se carga la semilla: cada test crea su tenant, rol, usuario y sesion. */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import({
    TestcontainersConfiguration.class,
    RequirePermissionTest.MethodLevelController.class,
    RequirePermissionTest.ClassLevelController.class
})
class RequirePermissionTest {

    private static final String METHOD_LEVEL = "/api/v1/test-support/permission/method";
    private static final String CLASS_LEVEL = "/api/v1/test-support/permission/class";
    private static final String PERMISSION = "test.permission";

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
    void roleWithPermissionIsAllowed() throws Exception {
        Tenant tenant = tenant();
        String token = tokenFor(tenant, role(tenant, RoleStatus.active, PERMISSION, "otro.permiso"));

        call(METHOD_LEVEL, token).andExpect(status().isOk());
        call(CLASS_LEVEL, token).andExpect(status().isOk());
    }

    @Test
    void roleWithoutPermissionIsForbidden() throws Exception {
        Tenant tenant = tenant();
        String token = tokenFor(tenant, role(tenant, RoleStatus.active, "otro.permiso"));

        call(METHOD_LEVEL, token)
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("ACCESS_DENIED"));
        call(CLASS_LEVEL, token).andExpect(status().isForbidden());
    }

    @Test
    void userWithoutRoleIsForbidden() throws Exception {
        Tenant tenant = tenant();

        call(METHOD_LEVEL, tokenFor(tenant, null)).andExpect(status().isForbidden());
    }

    @Test
    void inactiveOrArchivedRoleIsForbidden() throws Exception {
        Tenant tenant = tenant();

        call(METHOD_LEVEL, tokenFor(tenant, role(tenant, RoleStatus.inactive, PERMISSION)))
                .andExpect(status().isForbidden());
        call(METHOD_LEVEL, tokenFor(tenant, role(tenant, RoleStatus.archived, PERMISSION)))
                .andExpect(status().isForbidden());
    }

    @Test
    void roleFromAnotherTenantIsForbidden() throws Exception {
        Tenant tenant = tenant();
        Role foreignRole = role(tenant(), RoleStatus.active, PERMISSION);

        call(METHOD_LEVEL, tokenFor(tenant, foreignRole)).andExpect(status().isForbidden());
    }

    @Test
    void withoutTokenIsUnauthorized() throws Exception {
        mockMvc.perform(get(METHOD_LEVEL)).andExpect(status().isUnauthorized());
        mockMvc.perform(get(CLASS_LEVEL)).andExpect(status().isUnauthorized());
    }

    private ResultActions call(String path, String token) throws Exception {
        return mockMvc.perform(get(path).header("Authorization", "Bearer " + token));
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

    private Role role(Tenant tenant, RoleStatus status, String... permissions) {
        Role role = Role.builder()
                .name("Rol " + UUID.randomUUID())
                .status(status)
                .permissions(List.of(permissions))
                .build();
        role.setTenantId(tenant.getId());
        return roleRepository.save(role);
    }

    /** Usuario empleado del tenant con el rol dado (puede ser null) y una sesion real para su token. */
    private String tokenFor(Tenant tenant, Role role) {
        User user = User.builder()
                .name("Empleado test")
                .email("user-" + UUID.randomUUID() + "@test.local")
                .type(UserType.employee)
                .roleId(role == null ? null : role.getId())
                .build();
        user.setTenantId(tenant.getId());
        user = userRepository.save(user);
        Session session = sessionRepository.save(Session.builder()
                .userId(user.getId())
                .expiresAt(Instant.now().plus(1, ChronoUnit.HOURS).truncatedTo(ChronoUnit.SECONDS))
                .build());
        return jwtService.generateToken(user, session);
    }

    @RestController
    static class MethodLevelController {

        /** ApiPathConfig antepone {@code /api/v1} a todo controller del paquete base. */
        @RequirePermission(PERMISSION)
        @GetMapping("/test-support/permission/method")
        public String method() {
            return "ok";
        }
    }

    @RestController
    @RequirePermission(PERMISSION)
    static class ClassLevelController {

        @GetMapping("/test-support/permission/class")
        public String classLevel() {
            return "ok";
        }
    }
}
