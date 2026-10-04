package com.omniretail.backend.purchasing;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.omniretail.backend.TestcontainersConfiguration;
import com.omniretail.backend.administration.entity.Supplier;
import com.omniretail.backend.administration.entity.SupplierStatus;
import com.omniretail.backend.administration.entity.Tenant;
import com.omniretail.backend.administration.entity.TenantStatus;
import com.omniretail.backend.administration.entity.User;
import com.omniretail.backend.administration.entity.UserType;
import com.omniretail.backend.administration.repository.SupplierRepository;
import com.omniretail.backend.administration.repository.TenantRepository;
import com.omniretail.backend.auth.entity.Session;
import com.omniretail.backend.auth.service.JwtService;
import com.omniretail.backend.auth.service.SessionService;
import com.omniretail.backend.shared.security.PermissionResolver;
import com.omniretail.backend.shared.security.SaasCapability;
import com.omniretail.backend.shared.security.TenantEntitlementResolver;
import com.omniretail.backend.shared.security.TenantEntitlements;
import java.time.Instant;
import java.util.EnumSet;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(TestcontainersConfiguration.class)
class PurchasingSupplierControllerIntegrationTest {

    private static final String LIST = "/api/v1/purchasing/suppliers";
    private static final String BASE = LIST + "/active";

    @Autowired private MockMvc mvc;
    @Autowired private JwtService jwtService;
    @Autowired private TenantRepository tenantRepository;
    @Autowired private SupplierRepository supplierRepository;
    @MockitoBean private SessionService sessions;
    @MockitoBean private PermissionResolver permissions;
    @MockitoBean private TenantEntitlementResolver entitlements;

    @BeforeEach
    void setUp() {
        given(sessions.isActive(any(), any())).willReturn(true);
        given(permissions.hasPermission(any(), any(), anyString())).willReturn(false);
        given(entitlements.resolve(any())).willReturn(
                new TenantEntitlements(true, true, EnumSet.allOf(SaasCapability.class)));
    }

    @Test
    void operationalUserCanListActiveSuppliersWithoutAdministrativePermission() throws Exception {
        Tenant tenant = tenant();
        Supplier supplier = supplier(tenant, "Proveedor operativo", SupplierStatus.active, 4);
        given(permissions.hasPermission(any(), any(), eq("purchasing.orders.create"))).willReturn(true);

        mvc.perform(get(BASE).header("Authorization", token(tenant)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].id").value(supplier.getId().toString()))
                .andExpect(jsonPath("$[0].name").value("Proveedor operativo"))
                .andExpect(jsonPath("$[0].leadTimeDays").value(4))
                .andExpect(jsonPath("$[0].status").value("active"))
                .andExpect(jsonPath("$[0].tenantId").doesNotExist())
                .andExpect(jsonPath("$[0].taxId").doesNotExist())
                .andExpect(jsonPath("$[0].notes").doesNotExist());

        verify(permissions, never()).hasPermission(any(), any(), eq("admin.suppliers.manage"));
    }

    @Test
    void inactiveAndArchivedSuppliersAreExcluded() throws Exception {
        Tenant tenant = tenant();
        supplier(tenant, "Activo", SupplierStatus.active, null);
        supplier(tenant, "Inactivo", SupplierStatus.inactive, null);
        supplier(tenant, "Archivado", SupplierStatus.archived, null);
        given(permissions.hasPermission(any(), any(), eq("purchasing.orders.read"))).willReturn(true);

        mvc.perform(get(BASE).header("Authorization", token(tenant)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].name").value("Activo"));
    }

    @Test
    void listIsScopedToAuthenticatedTenantAndRequestCannotOverrideIt() throws Exception {
        Tenant tenant = tenant();
        Tenant otherTenant = tenant();
        supplier(tenant, "Proveedor propio", SupplierStatus.active, null);
        supplier(otherTenant, "Proveedor ajeno", SupplierStatus.active, null);
        given(permissions.hasPermission(any(), any(), eq("purchasing.orders.approve"))).willReturn(true);

        mvc.perform(get(BASE)
                        .header("Authorization", token(tenant))
                        .param("tenantId", otherTenant.getId().toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].name").value("Proveedor propio"));
    }

    @Test
    void userWithoutOperationalPermissionsIsForbidden() throws Exception {
        Tenant tenant = tenant();

        mvc.perform(get(BASE).header("Authorization", token(tenant)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("ACCESS_DENIED"));
    }

    @Test
    void purchasingCapabilityIsRequired() throws Exception {
        Tenant tenant = tenant();
        given(permissions.hasPermission(any(), any(), eq("purchasing.orders.read"))).willReturn(true);
        given(entitlements.resolve(tenant.getId())).willReturn(
                new TenantEntitlements(true, true, EnumSet.of(SaasCapability.inventory)));

        mvc.perform(get(BASE).header("Authorization", token(tenant)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("CAPABILITY_REQUIRED"));
    }

    @Test
    void listIsPaginatedInDatabaseWithStableNameOrder() throws Exception {
        Tenant tenant = tenant();
        supplier(tenant, "Beta", SupplierStatus.active, null);
        supplier(tenant, "Alfa", SupplierStatus.inactive, null);
        supplier(tenant, "Gamma", SupplierStatus.archived, null);
        given(permissions.hasPermission(any(), any(), eq("purchasing.orders.read"))).willReturn(true);

        mvc.perform(get(LIST).header("Authorization", token(tenant)).param("page", "1").param("size", "2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.page").value(1))
                .andExpect(jsonPath("$.pageSize").value(2))
                .andExpect(jsonPath("$.totalItems").value(3))
                .andExpect(jsonPath("$.totalPages").value(2))
                .andExpect(jsonPath("$.items.length()").value(2))
                .andExpect(jsonPath("$.items[0].name").value("Alfa"))
                .andExpect(jsonPath("$.items[1].name").value("Beta"))
                .andExpect(jsonPath("$.items[0].tenantId").doesNotExist())
                .andExpect(jsonPath("$.items[0].address").doesNotExist())
                .andExpect(jsonPath("$.items[0].notes").doesNotExist());
        mvc.perform(get(LIST).header("Authorization", token(tenant)).param("page", "2").param("size", "2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.page").value(2))
                .andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.items[0].name").value("Gamma"));
    }

    @Test
    void searchIsCaseInsensitiveOverNameLegalNameTaxIdEmailAndTreatsWildcardsLiterally() throws Exception {
        Tenant tenant = tenant();
        supplier(tenant, "Distribuidora Norte", "Norte Sociedad Anonima", "1234567-8", SupplierStatus.active);
        supplier(tenant, "Otro", "Otra Razon", "999-K", SupplierStatus.active);
        given(permissions.hasPermission(any(), any(), eq("purchasing.orders.read"))).willReturn(true);

        for (String term : new String[] {"distribuidora", "NORTE SOCIEDAD", "1234567", "norte@example"}) {
            mvc.perform(get(LIST).header("Authorization", token(tenant)).param("search", term))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.totalItems").value(1))
                    .andExpect(jsonPath("$.items[0].name").value("Distribuidora Norte"));
        }
        mvc.perform(get(LIST).header("Authorization", token(tenant)).param("search", "%"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalItems").value(0));
        mvc.perform(get(LIST).header("Authorization", token(tenant)).param("search", "   "))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalItems").value(2));
    }

    @Test
    void statusFilterUsesRealSupplierStatusesAndDefaultsToAll() throws Exception {
        Tenant tenant = tenant();
        supplier(tenant, "Activo", SupplierStatus.active, null);
        supplier(tenant, "Inactivo", SupplierStatus.inactive, null);
        supplier(tenant, "Archivado", SupplierStatus.archived, null);
        given(permissions.hasPermission(any(), any(), eq("purchasing.orders.read"))).willReturn(true);

        mvc.perform(get(LIST).header("Authorization", token(tenant)).param("status", "inactive"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalItems").value(1))
                .andExpect(jsonPath("$.items[0].status").value("inactive"));
        mvc.perform(get(LIST).header("Authorization", token(tenant)).param("status", "archived"))
                .andExpect(jsonPath("$.items[0].name").value("Archivado"));
        mvc.perform(get(LIST).header("Authorization", token(tenant)))
                .andExpect(jsonPath("$.totalItems").value(3));
        mvc.perform(get(LIST).header("Authorization", token(tenant)).param("status", "bogus"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void listAndDetailAreIsolatedByTenant() throws Exception {
        Tenant tenant = tenant();
        Tenant other = tenant();
        supplier(tenant, "Propio", SupplierStatus.active, null);
        Supplier foreign = supplier(other, "Ajeno", SupplierStatus.active, null);
        given(permissions.hasPermission(any(), any(), eq("purchasing.orders.read"))).willReturn(true);

        mvc.perform(get(LIST)
                        .header("Authorization", token(tenant))
                        .param("tenantId", other.getId().toString()))
                .andExpect(jsonPath("$.totalItems").value(1))
                .andExpect(jsonPath("$.items[0].name").value("Propio"));
        mvc.perform(get(LIST + "/" + foreign.getId()).header("Authorization", token(tenant)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("SUPPLIER_NOT_FOUND"));
    }

    @Test
    void detailReturnsListFieldsPlusAddressAndNotes() throws Exception {
        Tenant tenant = tenant();
        Supplier supplier = supplier(tenant, "Detalle", "Detalle S.A.", "555-5", SupplierStatus.active);
        given(permissions.hasPermission(any(), any(), eq("purchasing.orders.approve"))).willReturn(true);

        mvc.perform(get(LIST + "/" + supplier.getId()).header("Authorization", token(tenant)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(supplier.getId().toString()))
                .andExpect(jsonPath("$.name").value("Detalle"))
                .andExpect(jsonPath("$.legalName").value("Detalle S.A."))
                .andExpect(jsonPath("$.taxId").value("555-5"))
                .andExpect(jsonPath("$.email").value("detalle@example.com"))
                .andExpect(jsonPath("$.phone").value("5555-1234"))
                .andExpect(jsonPath("$.address").value("Zona 1"))
                .andExpect(jsonPath("$.notes").value("Notas internas"))
                .andExpect(jsonPath("$.status").value("active"))
                .andExpect(jsonPath("$.tenantId").doesNotExist());
        verify(permissions, never()).hasPermission(any(), any(), eq("admin.suppliers.manage"));
    }

    @Test
    void listAndDetailRequirePermissionAndCapability() throws Exception {
        Tenant tenant = tenant();
        Supplier supplier = supplier(tenant, "Proveedor", SupplierStatus.active, null);

        mvc.perform(get(LIST).header("Authorization", token(tenant)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("ACCESS_DENIED"));
        mvc.perform(get(LIST + "/" + supplier.getId()).header("Authorization", token(tenant)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("ACCESS_DENIED"));

        given(permissions.hasPermission(any(), any(), eq("purchasing.orders.read"))).willReturn(true);
        given(entitlements.resolve(tenant.getId())).willReturn(
                new TenantEntitlements(true, true, EnumSet.of(SaasCapability.inventory)));
        mvc.perform(get(LIST).header("Authorization", token(tenant)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("CAPABILITY_REQUIRED"));
        mvc.perform(get(LIST + "/" + supplier.getId()).header("Authorization", token(tenant)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("CAPABILITY_REQUIRED"));
    }

    @Test
    void activeEndpointIsNotShadowedByTheDetailRoute() throws Exception {
        Tenant tenant = tenant();
        supplier(tenant, "Activo", SupplierStatus.active, null);
        supplier(tenant, "Inactivo", SupplierStatus.inactive, null);
        given(permissions.hasPermission(any(), any(), eq("purchasing.orders.read"))).willReturn(true);

        mvc.perform(get(LIST + "/active").header("Authorization", token(tenant)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].name").value("Activo"));
    }

    private Tenant tenant() {
        String suffix = UUID.randomUUID().toString();
        return tenantRepository.save(Tenant.builder()
                .name("Tenant " + suffix)
                .slug("purchasing-supplier-" + suffix)
                .status(TenantStatus.active)
                .defaultCurrency("GTQ")
                .timezone("America/Guatemala")
                .build());
    }

    private Supplier supplier(
            Tenant tenant, String name, String legalName, String taxId, SupplierStatus status) {
        Supplier supplier = Supplier.builder()
                .name(name)
                .legalName(legalName)
                .taxId(taxId)
                .email(name.toLowerCase().replace(" ", "") + "@example.com")
                .phone("5555-1234")
                .address("Zona 1")
                .notes("Notas internas")
                .status(status)
                .build();
        supplier.setTenantId(tenant.getId());
        return supplierRepository.save(supplier);
    }

    private Supplier supplier(Tenant tenant, String name, SupplierStatus status, Integer leadTimeDays) {
        Supplier supplier = Supplier.builder()
                .name(name)
                .status(status)
                .leadTimeDays(leadTimeDays)
                .build();
        supplier.setTenantId(tenant.getId());
        return supplierRepository.save(supplier);
    }

    private String token(Tenant tenant) {
        User user = User.builder()
                .name("Comprador")
                .email("purchasing-" + UUID.randomUUID() + "@test.local")
                .type(UserType.employee)
                .roleId(UUID.randomUUID())
                .build();
        user.setTenantId(tenant.getId());
        ReflectionTestUtils.setField(user, "id", UUID.randomUUID());
        Session session = Session.builder()
                .id(UUID.randomUUID())
                .userId(user.getId())
                .expiresAt(Instant.now().plusSeconds(3600))
                .build();
        return "Bearer " + jwtService.generateToken(user, session);
    }
}
