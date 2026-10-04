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

    private static final String BASE = "/api/v1/purchasing/suppliers/active";

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
