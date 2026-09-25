package com.omniretail.backend.catalog.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.omniretail.backend.TestcontainersConfiguration;
import com.omniretail.backend.administration.entity.User;
import com.omniretail.backend.administration.entity.UserType;
import com.omniretail.backend.auth.entity.Session;
import com.omniretail.backend.auth.service.JwtService;
import com.omniretail.backend.auth.service.SessionService;
import com.omniretail.backend.catalog.dto.ProductChannelsDto;
import com.omniretail.backend.catalog.dto.ProductCreateRequest;
import com.omniretail.backend.catalog.dto.ProductDto;
import com.omniretail.backend.catalog.dto.ProductTrackingDto;
import com.omniretail.backend.catalog.entity.ProductStatus;
import com.omniretail.backend.catalog.entity.ProductType;
import com.omniretail.backend.catalog.service.ProductService;
import com.omniretail.backend.shared.dto.PageResponse;
import com.omniretail.backend.shared.exception.BusinessException;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.Pageable;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(TestcontainersConfiguration.class)
class ProductControllerTest {

    private static final String PRODUCTS = "/api/v1/catalog/products";
    private static final UUID TENANT_ID = UUID.randomUUID();

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JwtService jwtService;

    @MockitoBean
    private ProductService productService;

    @MockitoBean
    private SessionService sessionService;

    @BeforeEach
    void setUp() {
        given(sessionService.isActive(any(), any())).willReturn(true);
    }

    @Test
    void unauthenticatedRequestIsRejected() throws Exception {
        mockMvc.perform(get(PRODUCTS)).andExpect(status().isUnauthorized());
    }

    @Test
    void authenticatedGetSucceeds() throws Exception {
        given(productService.list(any(Pageable.class)))
                .willReturn(new PageResponse<>(List.of(productDto()), 1, 20, 1, 1));

        mockMvc.perform(get(PRODUCTS).header("Authorization", token()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].tenantId").value(TENANT_ID.toString()))
                .andExpect(jsonPath("$.items[0].tracking.stock").value(true))
                .andExpect(jsonPath("$.items[0].channels.mobileApp").value(false));
    }

    @Test
    void pageableParametersAreAccepted() throws Exception {
        given(productService.list(any(Pageable.class)))
                .willReturn(new PageResponse<>(List.of(), 1, 10, 0, 0));

        mockMvc.perform(get(PRODUCTS)
                        .header("Authorization", token())
                        .param("page", "1")
                        .param("size", "10"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.page").value(1));

        ArgumentCaptor<Pageable> captor = ArgumentCaptor.forClass(Pageable.class);
        org.mockito.Mockito.verify(productService).list(captor.capture());
        assertThat(captor.getValue().getPageNumber()).isZero();
        assertThat(captor.getValue().getPageSize()).isEqualTo(10);
    }

    @Test
    void openApiDocumentsProductRequestAndPaginationContract() throws Exception {
        mockMvc.perform(get("/api-docs"))
                .andExpect(status().isOk())
                .andExpect(jsonPath(
                                "$.paths['/api/v1/catalog/products'].get.parameters[?(@.name == 'page')].schema.type")
                        .value(org.hamcrest.Matchers.contains("integer")))
                .andExpect(jsonPath(
                                "$.paths['/api/v1/catalog/products'].get.parameters[?(@.name == 'page')].schema.default")
                        .value(org.hamcrest.Matchers.contains(1)))
                .andExpect(jsonPath(
                                "$.paths['/api/v1/catalog/products'].get.parameters[?(@.name == 'page')].schema.minimum")
                        .value(org.hamcrest.Matchers.contains(1)))
                .andExpect(jsonPath(
                                "$.paths['/api/v1/catalog/products'].get.parameters[?(@.name == 'size')].schema.type")
                        .value(org.hamcrest.Matchers.contains("integer")))
                .andExpect(jsonPath(
                                "$.paths['/api/v1/catalog/products'].get.parameters[?(@.name == 'sort')].schema.type")
                        .value(org.hamcrest.Matchers.contains("string")))
                .andExpect(content().string(org.hamcrest.Matchers.not(
                        org.hamcrest.Matchers.containsString("lotExpirationConfigurationValid"))));
    }

    @Test
    void validPostSucceeds() throws Exception {
        given(productService.create(any(ProductCreateRequest.class))).willReturn(productDto());

        mockMvc.perform(post(PRODUCTS)
                        .header("Authorization", token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validBody(null)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.sku").value("SKU-001"))
                .andExpect(jsonPath("$.tracking.lot").value(true))
                .andExpect(jsonPath("$.channels.ecommerce").value(true));
    }

    @Test
    void invalidRequiredDataIsRejected() throws Exception {
        mockMvc.perform(post(PRODUCTS)
                        .header("Authorization", token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
    }

    @Test
    void lotAndExpirationMustBeConfiguredTogether() throws Exception {
        String body = validBody(null).replace("\"expiration\": true", "\"expiration\": false");

        mockMvc.perform(post(PRODUCTS)
                        .header("Authorization", token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
    }

    @Test
    void malformedEnumIsRejected() throws Exception {
        String body = validBody(null).replace("\"physical\"", "\"invented\"");

        mockMvc.perform(post(PRODUCTS)
                        .header("Authorization", token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest());
    }

    @Test
    void tenantIdPropertyCannotChangeEffectiveTenant() throws Exception {
        UUID injectedTenantId = UUID.randomUUID();
        given(productService.create(any(ProductCreateRequest.class))).willReturn(productDto());

        mockMvc.perform(post(PRODUCTS)
                        .header("Authorization", token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validBody(injectedTenantId)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.tenantId").value(TENANT_ID.toString()));

        ArgumentCaptor<ProductCreateRequest> captor = ArgumentCaptor.forClass(ProductCreateRequest.class);
        org.mockito.Mockito.verify(productService).create(captor.capture());
        assertThat(captor.getValue().getClass().getRecordComponents())
                .noneMatch(component -> component.getName().equals("tenantId"));
    }

    @Test
    void duplicateConflictUsesProjectErrorResponse() throws Exception {
        given(productService.create(any(ProductCreateRequest.class)))
                .willThrow(BusinessException.conflict("PRODUCT_SKU_CONFLICT", "Ya existe un producto con ese SKU."));

        mockMvc.perform(post(PRODUCTS)
                        .header("Authorization", token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validBody(null)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("PRODUCT_SKU_CONFLICT"));
    }

    private String token() {
        User user = User.builder()
                .name("Usuario catalogo")
                .email("catalogo@test.local")
                .type(UserType.employee)
                .build();
        ReflectionTestUtils.setField(user, "id", UUID.randomUUID());
        user.setTenantId(TENANT_ID);
        Session session = Session.builder()
                .id(UUID.randomUUID())
                .userId(user.getId())
                .expiresAt(Instant.now().plus(1, ChronoUnit.HOURS))
                .build();
        return "Bearer " + jwtService.generateToken(user, session);
    }

    private static String validBody(UUID injectedTenantId) {
        String tenantProperty = injectedTenantId == null ? "" : "\"tenantId\": \"" + injectedTenantId + "\",";
        return """
                {
                  %s
                  "sku": "SKU-001",
                  "barcode": "123456789",
                  "name": "Producto",
                  "description": "Descripcion",
                  "brand": "Marca",
                  "productType": "physical",
                  "categoryId": "%s",
                  "baseUnitId": "%s",
                  "inventoryUnitId": "%s",
                  "saleUnitId": "%s",
                  "salePrice": 25.50,
                  "status": "published",
                  "tracking": {"stock": true, "lot": true, "expiration": true, "serial": false},
                  "channels": {"ecommerce": true, "pos": true, "mobileApp": false}
                }
                """.formatted(
                tenantProperty, UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID());
    }

    private static ProductDto productDto() {
        return new ProductDto(
                UUID.randomUUID(),
                TENANT_ID,
                "SKU-001",
                "123456789",
                "Producto",
                "Descripcion",
                "Marca",
                ProductType.physical,
                UUID.randomUUID(),
                UUID.randomUUID(),
                UUID.randomUUID(),
                UUID.randomUUID(),
                new BigDecimal("25.50"),
                ProductStatus.published,
                new ProductTrackingDto(true, true, true, false),
                new ProductChannelsDto(true, true, false),
                Instant.parse("2026-01-01T00:00:00Z"),
                Instant.parse("2026-01-01T00:00:00Z"));
    }
}
