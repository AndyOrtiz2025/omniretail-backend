package com.omniretail.backend.administration.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.matchesPattern;
import static org.hamcrest.Matchers.nullValue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
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
import com.omniretail.backend.administration.entity.UserType;
import com.omniretail.backend.administration.repository.BranchRepository;
import com.omniretail.backend.administration.repository.RoleRepository;
import com.omniretail.backend.administration.repository.TenantRepository;
import com.omniretail.backend.administration.repository.UserRepository;
import com.omniretail.backend.auth.entity.Session;
import com.omniretail.backend.auth.repository.SessionRepository;
import com.omniretail.backend.auth.service.JwtService;
import com.omniretail.backend.shared.media.MediaStorageService;
import com.omniretail.backend.shared.security.SaasCapability;
import com.omniretail.backend.shared.security.TenantEntitlementResolver;
import com.omniretail.backend.shared.security.TenantEntitlements;
import com.jayway.jsonpath.JsonPath;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.EnumSet;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(TestcontainersConfiguration.class)
@TestPropertySource(properties = "app.media.storage-path=${java.io.tmpdir}/omniretail-ecommerce-media-test")
class EcommerceConfigControllerTest {

    private static final String ECOMMERCE_URL = "/api/v1/administration/ecommerce-config";
    private static final String HERO_URL = "/api/v1/administration/hero-banner";

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
    private BranchRepository branchRepository;

    @Autowired
    private MediaStorageService mediaStorageService;

    @MockitoBean
    private TenantEntitlementResolver entitlementResolver;

    @BeforeEach
    void setUp() {
        given(entitlementResolver.resolve(any(UUID.class)))
                .willReturn(new TenantEntitlements(true, true, EnumSet.allOf(SaasCapability.class)));
    }

    @Test
    void withoutTokenReturnsUnauthorized() throws Exception {
        mockMvc.perform(get(ECOMMERCE_URL)).andExpect(status().isUnauthorized());
        mockMvc.perform(get(HERO_URL)).andExpect(status().isUnauthorized());
    }

    @Test
    void withoutPermissionReturnsForbidden() throws Exception {
        Tenant tenant = persistTenant();
        String token = tokenFor(tenant, List.of());

        mockMvc.perform(get(ECOMMERCE_URL).header("Authorization", bearer(token)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("ACCESS_DENIED"));
        mockMvc.perform(get(HERO_URL).header("Authorization", bearer(token)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("ACCESS_DENIED"));
    }

    @Test
    void withoutEcommerceCapabilityReturnsForbidden() throws Exception {
        Tenant tenant = persistTenant();
        String token = tokenFor(tenant);
        given(entitlementResolver.resolve(tenant.getId()))
                .willReturn(new TenantEntitlements(true, true, EnumSet.of(SaasCapability.inventory)));

        mockMvc.perform(get(ECOMMERCE_URL).header("Authorization", bearer(token)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("CAPABILITY_REQUIRED"));

        mockMvc.perform(put(ECOMMERCE_URL)
                        .header("Authorization", bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(ecommerceBody(false, null)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("CAPABILITY_REQUIRED"));

        mockMvc.perform(get(HERO_URL).header("Authorization", bearer(token)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("CAPABILITY_REQUIRED"));

        mockMvc.perform(put(HERO_URL)
                        .header("Authorization", bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(slidesBody(3)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("CAPABILITY_REQUIRED"));
    }

    @Test
    void getWithoutConfigReturnsNotFound() throws Exception {
        Tenant tenant = persistTenant();
        String token = tokenFor(tenant);

        mockMvc.perform(get(ECOMMERCE_URL).header("Authorization", bearer(token)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("ECOMMERCE_CONFIG_NOT_FOUND"))
                .andExpect(jsonPath("$.message").value("No se encontró la configuración de e-commerce del negocio."));

        mockMvc.perform(get(HERO_URL).header("Authorization", bearer(token)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("HERO_BANNER_NOT_FOUND"))
                .andExpect(jsonPath("$.message").value("No hay un carrusel configurado para el negocio actual."));
    }

    @Test
    void phoneFollowsSameRuleAsFrontend() throws Exception {
        Tenant tenant = persistTenant();
        String token = tokenFor(tenant);

        // Solo cuentan los dígitos: prefijo 502 opcional y cualquier separador.
        mockMvc.perform(put(ECOMMERCE_URL)
                        .header("Authorization", bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(ecommerceBodyWithPhone("(502) 1234 5678")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.contactPhone").value("+502 1234-5678"));

        mockMvc.perform(put(ECOMMERCE_URL)
                        .header("Authorization", bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(ecommerceBodyWithPhone("9876.5432")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.contactPhone").value("+502 9876-5432"));

        mockMvc.perform(put(ECOMMERCE_URL)
                        .header("Authorization", bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(ecommerceBodyWithPhone("2222-333")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("El teléfono público debe tener 8 dígitos."));
    }

    @Test
    void putEnablingWithoutDefaultBranchFails() throws Exception {
        Tenant tenant = persistTenant();
        String token = tokenFor(tenant);

        mockMvc.perform(put(ECOMMERCE_URL)
                        .header("Authorization", bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(ecommerceBody(true, null)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message")
                        .value("Seleccione una sucursal predeterminada para habilitar el e-commerce."));
    }

    @Test
    void putWithNonExistentOrForeignBranchFails() throws Exception {
        Tenant tenant = persistTenant();
        Tenant otherTenant = persistTenant();
        String token = tokenFor(tenant);
        Branch foreignBranch = persistBranch(otherTenant, BranchStatus.active);

        mockMvc.perform(put(ECOMMERCE_URL)
                        .header("Authorization", bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(ecommerceBody(true, UUID.randomUUID())))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message")
                        .value("La sucursal predeterminada no existe o no pertenece al negocio activo."));

        mockMvc.perform(put(ECOMMERCE_URL)
                        .header("Authorization", bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(ecommerceBody(true, foreignBranch.getId())))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message")
                        .value("La sucursal predeterminada no existe o no pertenece al negocio activo."));
    }

    @Test
    void putWithInactiveBranchFails() throws Exception {
        Tenant tenant = persistTenant();
        String token = tokenFor(tenant);
        Branch inactiveBranch = persistBranch(tenant, BranchStatus.inactive);

        mockMvc.perform(put(ECOMMERCE_URL)
                        .header("Authorization", bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(ecommerceBody(true, inactiveBranch.getId())))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("La sucursal predeterminada debe estar activa."));
    }

    @Test
    void putWithValidConfigPersistsAndReturns() throws Exception {
        Tenant tenant = persistTenant();
        String token = tokenFor(tenant);
        Branch branch = persistBranch(tenant, BranchStatus.active);

        String body =
                """
                {"enabled":true,"storeName":"  Tienda Central  ","logoUrl":"https://cdn.example.com/logo.png","contactPhone":"22345678","contactEmail":"Ventas@Tienda.COM","requireAccountForCheckout":false,"guestTrackingEnabled":true,"allowedDeliveryMethods":["store_pickup"],"allowedPaymentMethods":["cash"],"defaultBranchId":"%s"}
                """
                        .formatted(branch.getId());

        mockMvc.perform(put(ECOMMERCE_URL)
                        .header("Authorization", bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.enabled").value(true))
                .andExpect(jsonPath("$.storeName").value("Tienda Central"))
                .andExpect(jsonPath("$.contactPhone").value("+502 2234-5678"))
                .andExpect(jsonPath("$.contactEmail").value("ventas@tienda.com"))
                .andExpect(jsonPath("$.allowedDeliveryMethods.length()").value(1))
                .andExpect(jsonPath("$.allowedDeliveryMethods[0]").value("home_delivery"))
                .andExpect(jsonPath("$.allowedPaymentMethods[0]").value("card"))
                .andExpect(jsonPath("$.defaultBranchId").value(branch.getId().toString()));

        mockMvc.perform(get(ECOMMERCE_URL).header("Authorization", bearer(token)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.enabled").value(true))
                .andExpect(jsonPath("$.storeName").value("Tienda Central"))
                .andExpect(jsonPath("$.logoUrl").value("https://cdn.example.com/logo.png"))
                .andExpect(jsonPath("$.requireAccountForCheckout").value(false))
                .andExpect(jsonPath("$.guestTrackingEnabled").value(true))
                .andExpect(jsonPath("$.defaultBranchId").value(branch.getId().toString()));
    }

    @Test
    void heroBannerWithoutExactlyThreeSlidesFails() throws Exception {
        Tenant tenant = persistTenant();
        String token = tokenFor(tenant);

        mockMvc.perform(put(HERO_URL)
                        .header("Authorization", bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(slidesBody(2)))
                .andExpect(status().isBadRequest());

        mockMvc.perform(put(HERO_URL)
                        .header("Authorization", bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(slidesBody(4)))
                .andExpect(status().isBadRequest());
    }

    @Test
    void heroBannerWithExceededLimitsFails() throws Exception {
        Tenant tenant = persistTenant();
        String token = tokenFor(tenant);

        String longTitle =
                """
                {"slides":[{"title":"%s","description":"Ok"},{"title":"B","description":"Ok"},{"title":"C","description":"Ok"}]}
                """
                        .formatted("T".repeat(81));

        mockMvc.perform(put(HERO_URL)
                        .header("Authorization", bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(longTitle))
                .andExpect(status().isBadRequest());

        String longDescription =
                """
                {"slides":[{"title":"A","description":"%s"},{"title":"B","description":"Ok"},{"title":"C","description":"Ok"}]}
                """
                        .formatted("D".repeat(161));

        mockMvc.perform(put(HERO_URL)
                        .header("Authorization", bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(longDescription))
                .andExpect(status().isBadRequest());
    }

    @Test
    void heroBannerPutPersistsAndReturns() throws Exception {
        Tenant tenant = persistTenant();
        String token = tokenFor(tenant);

        String body =
                """
                {"slides":[{"title":"  Ofertas  ","description":"Hasta 50%","imageUrl":"https://cdn.example.com/1.png"},{"title":"Nuevos","description":"Recién llegados","imageUrl":""},{"title":"Envíos","description":"A todo el país"}]}
                """;

        mockMvc.perform(put(HERO_URL)
                        .header("Authorization", bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.slides.length()").value(3))
                .andExpect(jsonPath("$.slides[0].title").value("Ofertas"))
                .andExpect(jsonPath("$.slides[1].imageUrl").value(nullValue()));

        mockMvc.perform(get(HERO_URL).header("Authorization", bearer(token)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.slides.length()").value(3))
                .andExpect(jsonPath("$.slides[0].title").value("Ofertas"))
                .andExpect(jsonPath("$.slides[0].imageUrl").value("https://cdn.example.com/1.png"))
                .andExpect(jsonPath("$.slides[2].description").value("A todo el país"));
    }

    @Test
    void tenantIsolationVerified() throws Exception {
        Tenant tenantA = persistTenant();
        Tenant tenantB = persistTenant();
        String tokenA = tokenFor(tenantA);
        String tokenB = tokenFor(tenantB);
        Branch branchA = persistBranch(tenantA, BranchStatus.active);

        mockMvc.perform(put(ECOMMERCE_URL)
                        .header("Authorization", bearer(tokenA))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(ecommerceBody(true, branchA.getId())))
                .andExpect(status().isOk());

        mockMvc.perform(put(HERO_URL)
                        .header("Authorization", bearer(tokenA))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(slidesBody(3)))
                .andExpect(status().isOk());

        // Tenant B no ve la configuración de A: para B todavía no existe.
        mockMvc.perform(get(ECOMMERCE_URL).header("Authorization", bearer(tokenB)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("ECOMMERCE_CONFIG_NOT_FOUND"));

        mockMvc.perform(get(HERO_URL).header("Authorization", bearer(tokenB)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("HERO_BANNER_NOT_FOUND"));

        // Tenant B no puede apuntar a una sucursal de A ni pisar la config de A.
        mockMvc.perform(put(ECOMMERCE_URL)
                        .header("Authorization", bearer(tokenB))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(ecommerceBody(true, branchA.getId())))
                .andExpect(status().isBadRequest());

        mockMvc.perform(put(ECOMMERCE_URL)
                        .header("Authorization", bearer(tokenB))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(ecommerceBody(false, null)))
                .andExpect(status().isOk());

        mockMvc.perform(get(ECOMMERCE_URL).header("Authorization", bearer(tokenA)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.tenantId").value(tenantA.getId().toString()))
                .andExpect(jsonPath("$.enabled").value(true))
                .andExpect(jsonPath("$.defaultBranchId").value(branchA.getId().toString()));

        mockMvc.perform(get(HERO_URL).header("Authorization", bearer(tokenA)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.slides[0].title").value("Slide 1"));
    }

    private static final byte[] PNG = {(byte) 0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a, 1};

    @Test
    void uploadEndpointsRequireTokenAndPermission() throws Exception {
        Tenant tenant = persistTenant();
        String withoutPermission = tokenFor(tenant, List.of());

        mockMvc.perform(multipart(ECOMMERCE_URL + "/logo").file(png()))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(multipart(ECOMMERCE_URL + "/logo").file(png())
                        .header("Authorization", bearer(withoutPermission)))
                .andExpect(status().isForbidden());
        mockMvc.perform(multipart(HERO_URL + "/slides/0/image").file(png())
                        .header("Authorization", bearer(withoutPermission)))
                .andExpect(status().isForbidden());
        mockMvc.perform(delete(ECOMMERCE_URL + "/logo").header("Authorization", bearer(withoutPermission)))
                .andExpect(status().isForbidden());
        mockMvc.perform(delete(HERO_URL + "/slides/0/image").header("Authorization", bearer(withoutPermission)))
                .andExpect(status().isForbidden());
    }

    @Test
    void uploadWithoutSavedConfigReturnsNotFound() throws Exception {
        Tenant tenant = persistTenant();
        String token = tokenFor(tenant);

        mockMvc.perform(multipart(ECOMMERCE_URL + "/logo").file(png()).header("Authorization", bearer(token)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("ECOMMERCE_CONFIG_NOT_FOUND"));
        mockMvc.perform(multipart(HERO_URL + "/slides/0/image").file(png()).header("Authorization", bearer(token)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("HERO_BANNER_NOT_FOUND"));
    }

    @Test
    void uploadLogoStoresRelativePathReplacesAndDeletesPreviousFile() throws Exception {
        Tenant tenant = persistTenant();
        String token = tokenFor(tenant);
        savedEcommerceConfig(token);
        String pattern = "^/media/" + tenant.getId() + "/ecommerce/" + tenant.getId() + "/[0-9a-f-]{36}\\.png$";

        String first = urlOf(mockMvc.perform(multipart(ECOMMERCE_URL + "/logo").file(png())
                        .header("Authorization", bearer(token)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.logoUrl").value(matchesPattern(pattern)))
                .andReturn()
                .getResponse()
                .getContentAsString());
        assertThat(Files.exists(fileFor(first))).isTrue();

        String second = urlOf(mockMvc.perform(multipart(ECOMMERCE_URL + "/logo").file(png())
                        .header("Authorization", bearer(token)))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString());

        assertThat(second).isNotEqualTo(first);
        assertThat(Files.exists(fileFor(second))).isTrue();
        assertThat(Files.exists(fileFor(first))).isFalse();
        mockMvc.perform(get(ECOMMERCE_URL).header("Authorization", bearer(token)))
                .andExpect(jsonPath("$.logoUrl").value(second));
    }

    @Test
    void uploadLogoRejectsInvalidAndSpoofedFiles() throws Exception {
        Tenant tenant = persistTenant();
        String token = tokenFor(tenant);
        savedEcommerceConfig(token);

        mockMvc.perform(multipart(ECOMMERCE_URL + "/logo")
                        .file(new MockMultipartFile("file", "logo.png", "image/png", "no-es-imagen".getBytes()))
                        .header("Authorization", bearer(token)))
                .andExpect(status().isUnsupportedMediaType())
                .andExpect(jsonPath("$.code").value("MEDIA_TYPE_INVALID"));
        mockMvc.perform(multipart(ECOMMERCE_URL + "/logo")
                        .file(new MockMultipartFile("file", "logo.jpg", "image/jpeg", PNG))
                        .header("Authorization", bearer(token)))
                .andExpect(status().isUnsupportedMediaType());
    }

    @Test
    void deleteLogoClearsReferenceAndRemovesFile() throws Exception {
        Tenant tenant = persistTenant();
        String token = tokenFor(tenant);
        savedEcommerceConfig(token);
        String url = urlOf(mockMvc.perform(multipart(ECOMMERCE_URL + "/logo").file(png())
                        .header("Authorization", bearer(token)))
                .andReturn()
                .getResponse()
                .getContentAsString());

        mockMvc.perform(delete(ECOMMERCE_URL + "/logo").header("Authorization", bearer(token)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.logoUrl").value(nullValue()));

        assertThat(Files.exists(fileFor(url))).isFalse();
    }

    @Test
    void putThatChangesOrClearsLogoDeletesPreviousManagedFile() throws Exception {
        Tenant tenant = persistTenant();
        String token = tokenFor(tenant);
        savedEcommerceConfig(token);
        String url = urlOf(mockMvc.perform(multipart(ECOMMERCE_URL + "/logo").file(png())
                        .header("Authorization", bearer(token)))
                .andReturn()
                .getResponse()
                .getContentAsString());

        // Reenviar la misma URL gestionada no la borra (asi se guarda el formulario sin tocar el logo).
        mockMvc.perform(put(ECOMMERCE_URL)
                        .header("Authorization", bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(ecommerceBodyWithLogo(url)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.logoUrl").value(url));
        assertThat(Files.exists(fileFor(url))).isTrue();

        mockMvc.perform(put(ECOMMERCE_URL)
                        .header("Authorization", bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(ecommerceBodyWithLogo("https://cdn.example.com/externo.png")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.logoUrl").value("https://cdn.example.com/externo.png"));

        assertThat(Files.exists(fileFor(url))).isFalse();
    }

    @Test
    void heroSlideImageUploadReplaceAndDeleteKeepOtherSlides() throws Exception {
        Tenant tenant = persistTenant();
        String token = tokenFor(tenant);
        mockMvc.perform(put(HERO_URL)
                        .header("Authorization", bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(slidesBody(3)))
                .andExpect(status().isOk());
        String pattern = "^/media/" + tenant.getId() + "/ecommerce/" + tenant.getId() + "/[0-9a-f-]{36}\\.png$";

        String first = slideImageOf(mockMvc.perform(multipart(HERO_URL + "/slides/1/image").file(png())
                        .header("Authorization", bearer(token)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.slides.length()").value(3))
                .andExpect(jsonPath("$.slides[1].imageUrl").value(matchesPattern(pattern)))
                .andExpect(jsonPath("$.slides[1].title").value("Slide 2"))
                .andExpect(jsonPath("$.slides[0].imageUrl").value(nullValue()))
                .andReturn()
                .getResponse()
                .getContentAsString(), 1);
        assertThat(Files.exists(fileFor(first))).isTrue();

        String second = slideImageOf(mockMvc.perform(multipart(HERO_URL + "/slides/1/image").file(png())
                        .header("Authorization", bearer(token)))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString(), 1);
        assertThat(Files.exists(fileFor(first))).isFalse();
        assertThat(Files.exists(fileFor(second))).isTrue();

        mockMvc.perform(delete(HERO_URL + "/slides/1/image").header("Authorization", bearer(token)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.slides[1].imageUrl").value(nullValue()))
                .andExpect(jsonPath("$.slides[2].title").value("Slide 3"));
        assertThat(Files.exists(fileFor(second))).isFalse();
    }

    @Test
    void heroSlideImageWithInvalidIndexFails() throws Exception {
        Tenant tenant = persistTenant();
        String token = tokenFor(tenant);
        mockMvc.perform(put(HERO_URL)
                        .header("Authorization", bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(slidesBody(3)))
                .andExpect(status().isOk());

        mockMvc.perform(multipart(HERO_URL + "/slides/3/image").file(png()).header("Authorization", bearer(token)))
                .andExpect(status().isBadRequest());
        mockMvc.perform(multipart(HERO_URL + "/slides/-1/image").file(png()).header("Authorization", bearer(token)))
                .andExpect(status().isBadRequest());
        mockMvc.perform(delete(HERO_URL + "/slides/3/image").header("Authorization", bearer(token)))
                .andExpect(status().isBadRequest());
    }

    @Test
    void heroPutRemovingManagedImageDeletesFileButKeepsReusedOnes() throws Exception {
        Tenant tenant = persistTenant();
        String token = tokenFor(tenant);
        mockMvc.perform(put(HERO_URL)
                        .header("Authorization", bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(slidesBody(3)))
                .andExpect(status().isOk());
        String kept = slideImageOf(mockMvc.perform(multipart(HERO_URL + "/slides/0/image").file(png())
                        .header("Authorization", bearer(token)))
                .andReturn()
                .getResponse()
                .getContentAsString(), 0);
        String removed = slideImageOf(mockMvc.perform(multipart(HERO_URL + "/slides/2/image").file(png())
                        .header("Authorization", bearer(token)))
                .andReturn()
                .getResponse()
                .getContentAsString(), 2);
        String body = "{\"slides\":[{\"title\":\"A\",\"description\":\"a\",\"imageUrl\":\"" + kept + "\"},"
                + "{\"title\":\"B\",\"description\":\"b\"},"
                + "{\"title\":\"C\",\"description\":\"c\"}]}";

        mockMvc.perform(put(HERO_URL)
                        .header("Authorization", bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.slides[0].imageUrl").value(kept));

        assertThat(Files.exists(fileFor(kept))).isTrue();
        assertThat(Files.exists(fileFor(removed))).isFalse();
    }

    private void savedEcommerceConfig(String token) throws Exception {
        mockMvc.perform(put(ECOMMERCE_URL)
                        .header("Authorization", bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(ecommerceBody(false, null)))
                .andExpect(status().isOk());
    }

    private static MockMultipartFile png() {
        return new MockMultipartFile("file", "logo.png", "image/png", PNG);
    }

    private Path fileFor(String url) {
        return mediaStorageService.root().resolve(url.substring("/media/".length()));
    }

    private static String urlOf(String responseBody) {
        return JsonPath.read(responseBody, "$.logoUrl");
    }

    private static String slideImageOf(String responseBody, int index) {
        return JsonPath.read(responseBody, "$.slides[" + index + "].imageUrl");
    }

    private static String ecommerceBodyWithLogo(String logoUrl) {
        return """
                {"enabled":false,"storeName":"Mi Tienda","logoUrl":"%s","requireAccountForCheckout":true,"guestTrackingEnabled":false}
                """
                .formatted(logoUrl);
    }

    private static String ecommerceBodyWithPhone(String contactPhone) {
        return """
                {"enabled":false,"storeName":"Tienda","contactPhone":"%s","requireAccountForCheckout":false,"guestTrackingEnabled":true}
                """
                .formatted(contactPhone);
    }

    private static String ecommerceBody(boolean enabled, UUID defaultBranchId) {
        String branch = defaultBranchId != null ? "\"" + defaultBranchId + "\"" : "null";
        return """
                {"enabled":%s,"storeName":"Mi Tienda","requireAccountForCheckout":true,"guestTrackingEnabled":false,"defaultBranchId":%s}
                """
                .formatted(enabled, branch);
    }

    private static String slidesBody(int count) {
        StringBuilder slides = new StringBuilder();
        for (int i = 1; i <= count; i++) {
            if (i > 1) {
                slides.append(',');
            }
            slides.append("{\"title\":\"Slide ")
                    .append(i)
                    .append("\",\"description\":\"Descripcion ")
                    .append(i)
                    .append("\"}");
        }
        return "{\"slides\":[" + slides + "]}";
    }

    private Branch persistBranch(Tenant tenant, BranchStatus status) {
        Branch branch = Branch.builder()
                .code("BR-" + UUID.randomUUID().toString().substring(0, 8))
                .name("Sucursal " + UUID.randomUUID())
                .type(BranchType.store)
                .status(status)
                .build();
        branch.setTenantId(tenant.getId());
        return branchRepository.save(branch);
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
        return tokenFor(tenant, List.of("admin.ecommerce_config.manage"));
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
