package com.omniretail.backend.administration.controller;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.omniretail.backend.TestcontainersConfiguration;
import com.omniretail.backend.administration.entity.Role;
import com.omniretail.backend.administration.entity.Supplier;
import com.omniretail.backend.administration.entity.SupplierStatus;
import com.omniretail.backend.administration.entity.Tenant;
import com.omniretail.backend.administration.entity.TenantStatus;
import com.omniretail.backend.administration.entity.User;
import com.omniretail.backend.administration.entity.UserType;
import com.omniretail.backend.administration.repository.RoleRepository;
import com.omniretail.backend.administration.repository.SupplierRepository;
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
class SupplierControllerTest {

    private static final String BASE_URL = "/api/v1/administration/suppliers";

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
    private SessionRepository sessionRepository;

    @Autowired
    private SupplierRepository supplierRepository;

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
    void createSupplierSuccess() throws Exception {
        Tenant tenant = persistTenant();
        String token = tokenFor(tenant);

        String body =
                """
                {"name":"Distribuidora Central","legalName":"Distribuidora Central S.A.","taxId":"1234567-8","email":"CONTACTO@Proveedor.Com","phone":"22345678","address":"Zona 4, Guatemala","notes":"Entrega en 5 dias"}
                """;

        mockMvc.perform(post(BASE_URL)
                        .header("Authorization", bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.name").value("Distribuidora Central"))
                .andExpect(jsonPath("$.legalName").value("Distribuidora Central S.A."))
                .andExpect(jsonPath("$.taxId").value("1234567-8"))
                .andExpect(jsonPath("$.email").value("contacto@proveedor.com"))
                .andExpect(jsonPath("$.phone").value("22345678"))
                .andExpect(jsonPath("$.address").value("Zona 4, Guatemala"))
                .andExpect(jsonPath("$.notes").value("Entrega en 5 dias"))
                .andExpect(jsonPath("$.status").value("active"))
                .andExpect(jsonPath("$.tenantId").value(tenant.getId().toString()));
    }

    @Test
    void createSupplierDuplicateNameConflict() throws Exception {
        Tenant tenant = persistTenant();
        String token = tokenFor(tenant);

        String body = """
                {"name":"Proveedor Uno"}
                """;

        mockMvc.perform(post(BASE_URL)
                        .header("Authorization", bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isCreated());

        String secondBody = """
                {"name":"PROVEEDOR UNO"}
                """;

        mockMvc.perform(post(BASE_URL)
                        .header("Authorization", bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(secondBody))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("SUPPLIER_NAME_EXISTS"));
    }

    @Test
    void createSupplierDuplicateTaxIdConflict() throws Exception {
        Tenant tenant = persistTenant();
        String token = tokenFor(tenant);

        String body = """
                {"name":"Proveedor A","taxId":"9999999-1"}
                """;

        mockMvc.perform(post(BASE_URL)
                        .header("Authorization", bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isCreated());

        String secondBody = """
                {"name":"Proveedor B","taxId":"9999999-1"}
                """;

        mockMvc.perform(post(BASE_URL)
                        .header("Authorization", bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(secondBody))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("SUPPLIER_TAX_ID_EXISTS"));
    }

    @Test
    void createSupplierWithRepeatedCfTaxIdAllowed() throws Exception {
        Tenant tenant = persistTenant();
        String token = tokenFor(tenant);

        String bodyOne = """
                {"name":"Cliente Final Uno","taxId":"CF"}
                """;

        mockMvc.perform(post(BASE_URL)
                        .header("Authorization", bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(bodyOne))
                .andExpect(status().isCreated());

        String bodyTwo = """
                {"name":"Cliente Final Dos","taxId":"c/f"}
                """;

        mockMvc.perform(post(BASE_URL)
                        .header("Authorization", bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(bodyTwo))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.taxId").value("c/f"));
    }

    @Test
    void createSupplierWithEquivalentTaxIdFormatConflicts() throws Exception {
        Tenant tenant = persistTenant();
        String token = tokenFor(tenant);

        String bodyOne = """
                {"name":"Proveedor Nit Uno","taxId":"1234567-K"}
                """;

        mockMvc.perform(post(BASE_URL)
                        .header("Authorization", bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(bodyOne))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.taxId").value("1234567-K"));

        String bodyTwo = """
                {"name":"Proveedor Nit Dos","taxId":"1234567k"}
                """;

        mockMvc.perform(post(BASE_URL)
                        .header("Authorization", bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(bodyTwo))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("SUPPLIER_TAX_ID_EXISTS"));
    }

    @Test
    void createSupplierReusesArchivedSupplierWithSameName() throws Exception {
        Tenant tenant = persistTenant();
        String token = tokenFor(tenant);
        Supplier archived = persistSupplier(tenant, "Proveedor Reciclado", "1111111-1", SupplierStatus.archived);

        String body = """
                {"name":"Proveedor Reciclado","taxId":"1111111-1","phone":"22334455"}
                """;

        mockMvc.perform(post(BASE_URL)
                        .header("Authorization", bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value(archived.getId().toString()))
                .andExpect(jsonPath("$.status").value("active"))
                .andExpect(jsonPath("$.phone").value("22334455"));

        mockMvc.perform(get(BASE_URL).header("Authorization", bearer(token)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalItems").value(1));
    }

    @Test
    void sameNameAndTaxIdAllowedAcrossTenants() throws Exception {
        Tenant tenantA = persistTenant();
        Tenant tenantB = persistTenant();
        String tokenA = tokenFor(tenantA);
        String tokenB = tokenFor(tenantB);

        String body = """
                {"name":"Proveedor Compartido","taxId":"5555555-5"}
                """;

        mockMvc.perform(post(BASE_URL)
                        .header("Authorization", bearer(tokenA))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isCreated());

        mockMvc.perform(post(BASE_URL)
                        .header("Authorization", bearer(tokenB))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isCreated());
    }

    @Test
    void crossTenantIsolation() throws Exception {
        Tenant tenantA = persistTenant();
        Tenant tenantB = persistTenant();
        String tokenB = tokenFor(tenantB);

        Supplier supplier = persistSupplier(tenantA, "Proveedor Aislado", null, SupplierStatus.active);

        mockMvc.perform(get(BASE_URL + "/" + supplier.getId()).header("Authorization", bearer(tokenB)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("SUPPLIER_NOT_FOUND"));

        String updateBody = """
                {"name":"Proveedor Editado","status":"active"}
                """;

        mockMvc.perform(put(BASE_URL + "/" + supplier.getId())
                        .header("Authorization", bearer(tokenB))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(updateBody))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("SUPPLIER_NOT_FOUND"));
    }

    @Test
    void updateSupplierSuccess() throws Exception {
        Tenant tenant = persistTenant();
        String token = tokenFor(tenant);
        Supplier supplier = persistSupplier(tenant, "Proveedor Original", null, SupplierStatus.active);

        String updateBody = """
                {"name":"Proveedor Actualizado","phone":"33445566","status":"inactive"}
                """;

        mockMvc.perform(put(BASE_URL + "/" + supplier.getId())
                        .header("Authorization", bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(updateBody))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("Proveedor Actualizado"))
                .andExpect(jsonPath("$.phone").value("33445566"))
                .andExpect(jsonPath("$.status").value("inactive"));
    }

    @Test
    void archiveSupplierSuccess() throws Exception {
        Tenant tenant = persistTenant();
        String token = tokenFor(tenant);
        Supplier supplier = persistSupplier(tenant, "Proveedor A Archivar", null, SupplierStatus.active);

        mockMvc.perform(delete(BASE_URL + "/" + supplier.getId()).header("Authorization", bearer(token)))
                .andExpect(status().isNoContent());

        mockMvc.perform(get(BASE_URL + "/" + supplier.getId()).header("Authorization", bearer(token)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("archived"));
    }

    @Test
    void listSuppliersWithStatusFilterAndPagination() throws Exception {
        Tenant tenant = persistTenant();
        String token = tokenFor(tenant);

        persistSupplier(tenant, "Proveedor Activo", null, SupplierStatus.active);
        persistSupplier(tenant, "Proveedor Inactivo", null, SupplierStatus.inactive);

        mockMvc.perform(get(BASE_URL).header("Authorization", bearer(token)).param("status", "active"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.items[0].name").value("Proveedor Activo"))
                .andExpect(jsonPath("$.page").value(1))
                .andExpect(jsonPath("$.pageSize").value(20))
                .andExpect(jsonPath("$.totalItems").value(1));

        mockMvc.perform(get(BASE_URL).header("Authorization", bearer(token)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalItems").value(2));
    }

    @Test
    void listActiveSuppliers() throws Exception {
        Tenant tenant = persistTenant();
        String token = tokenFor(tenant);

        persistSupplier(tenant, "Proveedor Activo Uno", null, SupplierStatus.active);
        persistSupplier(tenant, "Proveedor Archivado", null, SupplierStatus.archived);

        mockMvc.perform(get(BASE_URL + "/active").header("Authorization", bearer(token)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].name").value("Proveedor Activo Uno"));
    }

    @Test
    void createSupplierValidationBadRequest() throws Exception {
        Tenant tenant = persistTenant();
        String token = tokenFor(tenant);

        String blankNameBody = """
                {"name":""}
                """;

        mockMvc.perform(post(BASE_URL)
                        .header("Authorization", bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(blankNameBody))
                .andExpect(status().isBadRequest());

        String tooLongNameBody =
                """
                {"name":"%s"}
                """
                        .formatted("A".repeat(121));

        mockMvc.perform(post(BASE_URL)
                        .header("Authorization", bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(tooLongNameBody))
                .andExpect(status().isBadRequest());

        String invalidPhoneBody = """
                {"name":"Proveedor Telefono Invalido","phone":"123"}
                """;

        mockMvc.perform(post(BASE_URL)
                        .header("Authorization", bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(invalidPhoneBody))
                .andExpect(status().isBadRequest());
    }

    private Supplier persistSupplier(Tenant tenant, String name, String taxId, SupplierStatus status) {
        Supplier supplier = Supplier.builder().name(name).taxId(taxId).status(status).build();
        supplier.setTenantId(tenant.getId());
        return supplierRepository.save(supplier);
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
        return tokenFor(tenant, List.of("admin.suppliers.manage"));
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
